package com.naudio.app.playback

import com.naudio.core.model.Track
import com.naudio.core.player.PlaybackController
import com.naudio.core.player.PlaybackStatus
import com.naudio.data.repository.LibraryRepository
import com.naudio.data.repository.QueueRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Snapshot of the persistent playback queue owned by [PlaybackCoordinator].
 */
data class PlaybackQueueState(
    /** Playback queue, in playback order. Empty when nothing is queued. */
    val queue: List<Track> = emptyList(),
    /** Position in [queue] of the track that is currently (or last) loaded. */
    val currentIndex: Int? = null,
    /** One-shot playback error; cleared via [onErrorShown]. */
    val error: PlaybackError? = null,
) {
    /** The track the queue currently points at, or null outside a queue. */
    val currentTrack: Track?
        get() = currentIndex?.let { queue.getOrNull(it) }
}

/**
 * Owns all queue orchestration for playback (M10): the queue state and current
 * index, next/previous movement, ENDED auto-advance, just-in-time source
 * resolution through [LibraryRepository], and unplayable-track skipping with
 * the M9 error semantics (exactly one surfaced error per skip run, no loops,
 * safe boundaries).
 *
 * The queue is persisted through [QueueRepository] and restored at
 * construction: a relaunch shows the persisted queue/current track but never
 * starts playback by itself — the first Play press resolves and loads the
 * restored current item. Auto-advance is inert until media has actually been
 * loaded this session, so a restored queue can never self-start.
 *
 * All work runs on the injected [scope] (the owning ViewModel's scope); Media3
 * is only ever touched through [PlaybackController].
 */
class PlaybackCoordinator(
    private val playbackController: PlaybackController,
    private val libraryRepository: LibraryRepository,
    private val queueRepository: QueueRepository,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(PlaybackQueueState())

    /** Queue position + one-shot error for the presentation layer. */
    val state: StateFlow<PlaybackQueueState> = _state.asStateFlow()

    /** In-flight resolution/advance; cancelled by a newer user action. */
    private var resolveJob: Job? = null

    /** Startup restore; cancelled by any user queue action racing it. */
    private var restoreJob: Job? = null

    /** True once any media was loaded this session; gates auto-advance. */
    private var hasLoadedMedia = false

    /** Serializes queue-table mutations so rapid replaces cannot interleave. */
    private val persistMutex = Mutex()

    init {
        restoreJob = scope.launch { restore() }
        // Auto-advance: when the player reports ENDED and a next queue item
        // exists, resolve and load it. Queue management stays app-side; the
        // MediaSessionService and Media3 are untouched. Gated on a real load
        // so a restored queue never advances (or starts) on its own.
        scope.launch {
            playbackController.state
                .map { it.status }
                .distinctUntilChanged()
                .collect { status ->
                    if (status == PlaybackStatus.ENDED && hasLoadedMedia) {
                        advanceFromNextIndex()
                    }
                }
        }
    }

    /**
     * Restore the persisted queue and position into memory without loading
     * anything into the player: the UI can see and favorite the restored
     * current track, but no playback starts until the user asks for it.
     */
    private suspend fun restore() {
        val persisted = queueRepository.observeQueue().first()
        if (persisted.tracks.isEmpty()) return
        val index = persisted.currentIndex?.takeIf { it in persisted.tracks.indices }
        _state.update { it.copy(queue = persisted.tracks, currentIndex = index) }
    }

    /**
     * Replace the queue with [tracks] and start at [startIndex]. An empty
     * queue or an out-of-range index is ignored (no state change, no crash);
     * playback of the previously loaded item is untouched.
     */
    fun setQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        // The user acted before startup restore finished — it must not
        // overwrite the fresh queue with stale persisted state.
        restoreJob?.cancel()
        _state.update { it.copy(queue = tracks.toList(), currentIndex = startIndex) }
        scope.launch {
            persistMutex.withLock { queueRepository.replaceQueue(tracks, startIndex) }
        }
        startLoadAtCurrentIndex()
    }

    /**
     * Jump to the next queue item. At the final item there is nothing to
     * advance to, so the request is a safe no-op.
     */
    fun skipToNext() {
        advanceFromNextIndex()
    }

    /**
     * Jump to the previous queue item. Before the first item the current track
     * is restarted (resumed or, after a restore, first loaded) instead of
     * crashing.
     */
    fun skipToPrevious() {
        val index = _state.value.currentIndex
        if (index == null) {
            // No queue position yet: cannot go further back.
            return
        }
        if (index <= 0) {
            // Boundary: previous at the first item restarts the current track.
            resumeOrPlay()
            return
        }
        resolveJob?.cancel()
        commitIndex(index - 1)
        startLoadAtCurrentIndex()
    }

    /** Play/pause intent: resumes the restored item on the very first play. */
    fun onTogglePlayPause() {
        if (playbackController.state.value.isPlaying) {
            playbackController.pause()
        } else {
            resumeOrPlay()
        }
    }

    /** The playback error message was shown — clear it. */
    fun onErrorShown() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Resume playback of the current item. If the player has no media loaded
     * (the restored-queue case, or nothing ever played), the current item is
     * resolved and loaded first; otherwise this is a plain resume.
     */
    private fun resumeOrPlay() {
        val current = _state.value.currentTrack
        if (current != null && playbackController.state.value.track == null) {
            startLoadAtCurrentIndex()
        } else {
            playbackController.play()
        }
    }

    /** Commit an index change to memory and to the persisted queue state. */
    private fun commitIndex(index: Int) {
        _state.update { it.copy(currentIndex = index) }
        scope.launch {
            persistMutex.withLock { queueRepository.setCurrentIndex(index) }
        }
    }

    /**
     * Advance to the item after the current index and play it, skipping
     * unresolvable items. Never loops: when no playable item remains the queue
     * ends and the existing unavailable state is surfaced once.
     */
    private fun advanceFromNextIndex() {
        val index = _state.value.currentIndex
        val next = (index ?: -1) + 1
        val queue = _state.value.queue
        if (next !in queue.indices) {
            // Boundary (skip at the end, ENDED past the last item): keep the
            // position on the final item — the player's ENDED status conveys
            // completion, and the track stays visible/favoritable.
            return
        }
        resolveJob?.cancel()
        commitIndex(next)
        startLoadAtCurrentIndex()
    }

    /**
     * Resolve the track at the current index and load it. Unresolvable items
     * (metadata-only providers, network failures) are skipped forward; the
     * skip is surfaced once, not per item, to avoid snackbar spam. If no
     * playable item remains the queue stops gracefully.
     */
    private fun startLoadAtCurrentIndex() {
        val queue = _state.value.queue
        val startIndex = _state.value.currentIndex ?: return
        resolveJob?.cancel()
        resolveJob = scope.launch {
            var skipError: PlaybackError? = null
            var settledIndex = startIndex
            for (index in startIndex until queue.size) {
                val track = queue[index]
                settledIndex = index
                _state.update { it.copy(currentIndex = index) }
                val source = try {
                    libraryRepository.resolveSource(track)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    skipError = PlaybackError.UNRESOLVABLE
                    continue
                }
                if (source != null) {
                    hasLoadedMedia = true
                    _state.update { it.copy(error = skipError) }
                    playbackController.load(track, source)
                    persistSettledIndex(index)
                    return@launch
                }
                skipError = PlaybackError.UNAVAILABLE
            }
            // Every remaining item was unplayable: stop gracefully and
            // surface the failure exactly once, never loop. The index stays on
            // the unplayable item so the UI can still show it (and, e.g.,
            // favorite it) — no further advance happens without a new ENDED or
            // an explicit user skip.
            _state.update { it.copy(error = skipError ?: PlaybackError.UNAVAILABLE) }
            persistSettledIndex(settledIndex)
        }
    }

    /** Fire-and-forget position persistence for the settled queue index. */
    private fun persistSettledIndex(index: Int) {
        scope.launch {
            persistMutex.withLock { queueRepository.setCurrentIndex(index) }
        }
    }
}
