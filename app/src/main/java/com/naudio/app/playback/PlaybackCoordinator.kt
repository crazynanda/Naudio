package com.naudio.app.playback

import androidx.media3.common.Player
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
 * M11 adds direct queue manipulation for the visual queue: [jumpToQueueIndex]
 * and [removeQueueItem], both persisted through the same serialized
 * [QueueRepository.replaceQueue] path so rapid mutations cannot leave Room
 * with a stale queue/index combination.
 *
 * ## Why an unplayable item is skipped, not "fixed" (M20)
 *
 * [LibraryRepository.resolveSource] returning null is a legitimate, final
 * answer from the playback-provider chain — "this provider cannot play this
 * track" — and Naudio treats it as such. The coordinator's job is to move past
 * such an item and keep the queue playing, never to retry it against a
 * different or more capable mechanism. In particular, when a provider declines
 * to hand over a playable source because the source is protected, the correct
 * response is to skip the item and tell the user, not to escalate. That is a
 * provider CAPABILITY BOUNDARY, and it deliberately lives behind
 * `provider/api`'s `PlaybackProvider`, not here.
 *
 * ## Terminal vs. per-item failure (M20)
 *
 * A run that skips some items and finds a playable one surfaces
 * [PlaybackError.UNAVAILABLE] once (the skip note) and keeps playing. A run
 * that reaches the end of the queue with nothing playable is a different
 * outcome: it surfaces [PlaybackError.QUEUE_UNPLAYABLE] and STOPS the player,
 * so the mobile UI and the Android Auto MediaSession never keep advertising a
 * stale media item while the visible queue points at an unplayable track.
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

    /**
     * M16: last observed Media3 repeat-loop counter. Together with the initial
     * `null` this distinguishes "the loop counter moved" from "this is merely
     * the first emission", so a repeat transition can be told apart from the
     * initial state (which must never advance the queue).
     *
     * Declared with the other coordinator state, ahead of `init`, so its
     * initializer is guaranteed to have run before the observer below reads
     * it regardless of the scope's dispatcher.
     */
    private var lastRepeatLoopCount: Long? = null

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
        // M16: queue-level REPEAT_MODE_ALL. Naudio's queue lives here while
        // Media3 holds a single-item timeline, so when the player loops that
        // item it never reports ENDED and the auto-advance above cannot fire.
        // Media3 still owns the repeat MODE (PlayerState mirrors it verbatim,
        // and Android Auto sees the same value); this only translates "the
        // player looped" into "the queue should move on" for REPEAT_MODE_ALL.
        // REPEAT_MODE_ONE and REPEAT_MODE_OFF are untouched: the player loops
        // the current track natively, and OFF keeps using the ENDED path.
        scope.launch {
            playbackController.state
                .map { it.repeatLoopCount to it.repeatMode }
                .distinctUntilChanged()
                .collect { (loopCount, repeatMode) ->
                    if (lastRepeatLoopCount != null &&
                        loopCount != lastRepeatLoopCount &&
                        repeatMode == Player.REPEAT_MODE_ALL &&
                        hasLoadedMedia
                    ) {
                        advanceForRepeatAll()
                    }
                    lastRepeatLoopCount = loopCount
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

    /**
     * Jump directly to the queue item at [index] (M11 queue UI). An invalid
     * index or an empty queue is a safe no-op. Jumping to the current index
     * does not reload the track — it only resumes (or, for a restored queue
     * whose item was never loaded, first loads) the current item. A valid
     * different index updates the position, persists it, and resolves/loads
     * the target through the existing just-in-time mechanism; the rest of the
     * queue is never resolved.
     */
    fun jumpToQueueIndex(index: Int) {
        val queue = _state.value.queue
        if (index !in queue.indices) return
        if (index == _state.value.currentIndex) {
            // Same item: no resolution/load — just ensure it is playing.
            resumeOrPlay()
            return
        }
        restoreJob?.cancel()
        resolveJob?.cancel()
        commitIndex(index)
        startLoadAtCurrentIndex()
    }

    /**
     * Remove the queue item at [index] (M11 queue UI). An invalid index or an
     * empty queue is a safe no-op.
     *
     * Removing an item that is not the current one only renumbers the queue
     * (before current: index decrements; after current: unchanged) — the
     * currently playing track is never reloaded or restarted. Removing the
     * current item promotes the next appropriate item (the item after it, or
     * the previous item when the last one is removed) and loads it exactly
     * once through the just-in-time mechanism. Removing the only item stops
     * playback gracefully and clears the persisted position.
     */
    fun removeQueueItem(index: Int) {
        val current = _state.value
        val queue = current.queue
        if (index !in queue.indices) return
        restoreJob?.cancel()
        resolveJob?.cancel()
        val removesCurrent = index == current.currentIndex
        val newQueue = queue.toMutableList().apply { removeAt(index) }
        val newIndex: Int? = when {
            !removesCurrent -> current.currentIndex?.let { if (index < it) it - 1 else it }
            newQueue.isEmpty() -> null
            // Next appropriate item: the one that followed the removed item,
            // or the previous item when the removed one was last.
            else -> minOf(index, newQueue.size - 1)
        }
        _state.update { it.copy(queue = newQueue, currentIndex = newIndex) }
        scope.launch {
            persistMutex.withLock { queueRepository.replaceQueue(newQueue, newIndex) }
        }
        when {
            !removesCurrent -> Unit // Playback untouched.
            newIndex == null -> stopPlayback() // Only item removed: stop gracefully.
            else -> startLoadAtCurrentIndex() // Genuine current replacement.
        }
    }

    // ------------------------------------------------------------------
    // M22: queue manipulation (play next / add to queue / clear / reorder)
    // ------------------------------------------------------------------

    /**
     * Insert [track] into the queue. If a track is currently playing (a valid
     * current index), the new track is inserted immediately after it and the
     * currently playing audio is never stopped or restarted. If there is no
     * active track, [setQueue] establishes the track as the queue and current
     * item through the existing playback architecture.
     */
    fun playNext(track: Track) {
        val currentIndex = _state.value.currentIndex
        val queue = _state.value.queue
        if (currentIndex != null && currentIndex in queue.indices) {
            // Active queue with a current track: insert after it. The new track
            // is queued but NOT loaded, so the current audio continues.
            //
            // currentIndex records the *loaded/playing* track, not the queue
            // position. It therefore stays on the old track; the item at
            // currentIndex + 1 is the next-to-play track (persisted as such).
            val newQueue = queue.toMutableList().apply { add(currentIndex + 1, track) }
            _state.update { it.copy(queue = newQueue, currentIndex = currentIndex) }
            scope.launch {
                persistMutex.withLock { queueRepository.replaceQueue(newQueue, currentIndex + 1) }
            }
        } else {
            // No active track: establish the track as the queue and current item
            // through the existing setQueue path (resolves and loads it).
            setQueue(listOf(track), startIndex = 0)
        }
    }

    /**
     * Append [tracks] to the end of the queue. The current index is preserved,
     * so playback is not interrupted and the current track is not restarted.
     */
    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val currentIndex = _state.value.currentIndex
        val newQueue = _state.value.queue + tracks
        _state.update { it.copy(queue = newQueue) }
        scope.launch {
            persistMutex.withLock { queueRepository.replaceQueue(newQueue, currentIndex) }
        }
    }

    /**
     * Move the item at [fromIndex] to [toIndex]. The current index is updated
     * to follow the moved item (or shifted by the renumbering), so the currently
     * playing audio is never restarted merely because another item was reordered.
     *
     * - Same from/to index → no-op.
     * - Out-of-range indices → safely ignored.
     * - Empty or single-item queue → safely handled.
     */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val queue = _state.value.queue
        val currentIndex = _state.value.currentIndex
        if (queue.isEmpty()) return
        if (fromIndex !in queue.indices || toIndex !in queue.indices) return
        if (fromIndex == toIndex) return
        val moving = queue[fromIndex]
        val isCurrent = fromIndex == currentIndex
        val newQueue = queue.toMutableList().apply { removeAt(fromIndex); add(toIndex, moving) }
        // The current item's new position:
        //   - if it was the moved item, it now sits at [toIndex];
        //   - otherwise it shifted by the removal/insertion.
        // newIndex is nullable; it is null only if there is no current item
        // (which cannot happen in this branch, but we keep it nullable for the
        // caller to persist safely).
        val newIndex: Int? = when {
            isCurrent -> toIndex
            else -> {
                // Queue is non-empty. If there is no persisted/current index
                // (e.g. a restored queue never played), no item is "current",
                // so the moved item's new position is the natural choice.
                val c = currentIndex
                if (c == null) toIndex
                else {
                    val ciAfterRemove = if (fromIndex < c) c - 1 else c
                    if (toIndex <= ciAfterRemove) ciAfterRemove + 1 else ciAfterRemove
                }
            }
        }
        _state.update { it.copy(queue = newQueue, currentIndex = newIndex) }
        scope.launch {
            persistMutex.withLock { queueRepository.replaceQueue(newQueue, newIndex) }
        }
    }

    /**
     * Clear the entire queue: stop playback, reset the queue/position state, and
     * persist an empty queue with no position so a restart shows an empty queue
     * and a stale ENDED cannot resurrect the previous one.
     */
    fun clearQueue() {
        // Stop the loaded media and close the auto-advance gate so no stale item
        // is advertised and no stray ENDED can restart the queue.
        stopPlayback()
        // A user action races startup restore: cancel it so it cannot win.
        restoreJob?.cancel()
        resolveJob?.cancel()
        // Fresh empty queue and position. The controller is idle (not resumed),
        // so nothing persists to be restored on a later launch.
        _state.update { PlaybackQueueState() }
        scope.launch {
            persistMutex.withLock { queueRepository.replaceQueue(emptyList(), null) }
        }
    }

    /**
     * Stop playback after the queue was emptied, or after a resolution run
     * found nothing playable (M20). The controller returns to idle and this
     * session's auto-advance gate closes; nothing is resolved or loaded, so
     * nothing can resurrect playback on its own.
     *
     * The queue, its position and the persisted position are deliberately left
     * intact: the UI can still show (and favorite) the track the queue points
     * at, and pressing Play simply starts a fresh resolution run.
     */
    private fun stopPlayback() {
        hasLoadedMedia = false
        playbackController.stop()
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
     * (the restored-queue case, nothing ever played, or M20's terminal stop),
     * the current item is resolved and loaded first; otherwise this is a plain
     * resume. This makes a terminal [PlaybackError.QUEUE_UNPLAYABLE] retryable
     * without any new mechanism.
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
            // completion, and the track stays visible/favoritable. Reaching the
            // end of a queue is a normal end, not a failure, so no error here.
            return
        }
        resolveJob?.cancel()
        commitIndex(next)
        startLoadAtCurrentIndex()
    }

    /**
     * M16: queue advance for REPEAT_MODE_ALL. Identical to
     * [advanceFromNextIndex] except that it wraps from the last queue item
     * back to the first, which is what "repeat all" means for Naudio's
     * coordinator-owned queue. Unresolvable items are skipped exactly as in
     * [advanceFromNextIndex], and a single-item queue simply reloads itself.
     */
    private fun advanceForRepeatAll() {
        val index = _state.value.currentIndex
        val queue = _state.value.queue
        if (queue.isEmpty()) return
        val next = if (index == null) 0 else (index + 1) % queue.size
        resolveJob?.cancel()
        commitIndex(next)
        startLoadAtCurrentIndex()
    }

    /**
     * Resolve the track at the current index and load it. Unresolvable items
     * (metadata-only providers, providers that cannot supply a playable source,
     * network failures) are skipped forward; the skip is surfaced once, not per
     * item, to avoid snackbar spam.
     *
     * Two outcomes:
     *  - a playable item is found → it is loaded, and any accumulated skip note
     *    is surfaced once alongside it (M9/M20 Cases A and B);
     *  - the queue is exhausted with nothing playable → this is a terminal
     *    outcome: [PlaybackError.QUEUE_UNPLAYABLE] is surfaced and the player is
     *    stopped so neither the mobile UI nor the Android Auto MediaSession is
     *    left showing a stale item (M20 Case C).
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
            // M20: every remaining item was unplayable. This is terminal for
            // the run and is deliberately reported as QUEUE_UNPLAYABLE rather
            // than the per-item UNAVAILABLE, so "we skipped one track" and
            // "nothing here can play" are never reported identically.
            //
            // Stopping the player (not merely leaving it alone) is what keeps
            // Media3/Android Auto unambiguous: without it a previously loaded
            // item would keep playing while the visible queue pointed at an
            // unplayable track. stopPlayback() also closes the auto-advance
            // gate, so no stray ENDED or repeat loop can restart the queue.
            //
            // The settled (not advanced-past) index is persisted and kept, so
            // the UI can still show and favorite the unplayable item, and a
            // later Play press re-runs resolution from it.
            stopPlayback()
            _state.update { it.copy(error = PlaybackError.QUEUE_UNPLAYABLE) }
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
