package com.naudio.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naudio.core.model.Track
import com.naudio.core.player.PlaybackController
import com.naudio.core.player.PlayerState
import com.naudio.core.player.PlaybackStatus
import com.naudio.data.repository.FavoritesRepository
import com.naudio.data.repository.LibraryRepository
import com.naudio.data.repository.TrackKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** User-visible reason the last playback request could not be served. */
enum class PlaybackError {
    /** No playback provider is registered for the track's provider — e.g. YouTube Music. */
    UNAVAILABLE,

    /** A playback provider exists but could not resolve a playable source (e.g. network failure). */
    UNRESOLVABLE,
}

/** Snapshot of the runtime playback queue and favorite state for the UI. */
data class PlaybackUiState(
    /** Runtime playback queue, in playback order. Empty when nothing is queued. */
    val queue: List<Track> = emptyList(),
    /** Position in [queue] of the track that is currently (or last) loaded. */
    val currentIndex: Int? = null,
    /** Provider identities of every favorited track, from the favorites repository. */
    val favoriteIds: Set<TrackKey> = emptySet(),
) {
    /** The track the queue currently points at, or null outside a queue. */
    val currentTrack: Track?
        get() = currentIndex?.let { queue.getOrNull(it) }

    /** Whether the current (or last loaded) track is favorited. */
    val isFavorite: Boolean
        get() = currentTrack?.let { TrackKey(it) in favoriteIds } == true
}

/**
 * Unidirectional data flow for playback: intents in ([onTrackSelected],
 * [setQueue], [skipToNext], [skipToPrevious], [onToggleFavorite], …), state out
 * ([playbackState], [uiState], [playbackError]). Delegates all audio to
 * [PlaybackController] and all source resolution to [LibraryRepository]; never
 * references Media3 or ExoPlayer.
 *
 * The queue is runtime-only (no persistence) and resolves each track's source
 * just-in-time — URLs are never resolved for the whole queue upfront.
 */
class PlaybackViewModel(
    private val playbackController: PlaybackController,
    private val libraryRepository: LibraryRepository,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {

    val playbackState: StateFlow<PlayerState> = playbackController.state

    private val _playbackError = MutableStateFlow<PlaybackError?>(null)

    /** One-shot playback error; cleared via [onErrorShown]. */
    val playbackError: StateFlow<PlaybackError?> = _playbackError.asStateFlow()

    private val _uiState = MutableStateFlow(PlaybackUiState())

    /** Queue position + derived favorite state for the now-playing UI. */
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    /** In-flight resolution/advance; cancelled by a newer user action. */
    private var resolveJob: Job? = null

    /** True while an advance (auto or user skip) is walking the queue. */
    private var isAdvancing = false

    init {
        // Favorite identities from Room, restated whenever favorites change.
        viewModelScope.launch {
            favoritesRepository.observeFavoriteIds().collect { ids ->
                _uiState.update { it.copy(favoriteIds = ids) }
            }
        }
        // Auto-advance: when the player reports ENDED and a next queue item
        // exists, resolve and load it. Queue management stays app-side; the
        // MediaSessionService and Media3 are untouched.
        viewModelScope.launch {
            playbackController.state
                .map { it.status }
                .distinctUntilChanged()
                .collect { status ->
                    if (status == PlaybackStatus.ENDED) {
                        advanceFromNextIndex(userInitiated = false)
                    }
                }
        }
    }

    /** Intent: user tapped a track — play it as a single-item queue. */
    fun onTrackSelected(track: Track) {
        setQueue(listOf(track), startIndex = 0)
    }

    /**
     * Intent: the user tapped the Library favorite at [index] — enqueue the
     * whole favorites list and start playback at that position.
     */
    fun onLibraryTrackSelected(favorites: List<Track>, index: Int) {
        setQueue(favorites, startIndex = index)
    }

    /**
     * Intent: replace the queue with [tracks] and start at [startIndex].
     * An empty queue or an out-of-range index is ignored (no state change,
     * no crash); playback of the previously loaded item is untouched.
     */
    fun setQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        _uiState.update { it.copy(queue = tracks.toList(), currentIndex = startIndex) }
        startLoadAtCurrentIndex()
    }

    /**
     * Intent: jump to the next queue item. At the final item there is nothing
     * to advance to, so the request is a safe no-op.
     */
    fun skipToNext() {
        advanceFromNextIndex(userInitiated = true)
    }

    /**
     * Intent: jump to the previous queue item. Before the first item the
     * current track is restarted by resuming playback instead of crashing.
     */
    fun skipToPrevious() {
        val index = _uiState.value.currentIndex
        if (index == null) {
            // No queue position yet: cannot go further back.
            return
        }
        if (index <= 0) {
            // Boundary: previous at the first item restarts the current track.
            playbackController.play()
            return
        }
        resolveJob?.cancel()
        _uiState.update { it.copy(currentIndex = index - 1) }
        startLoadAtCurrentIndex()
    }

    /** Intent: toggle the favorite state of the current track. */
    fun onToggleFavorite() {
        val track = _uiState.value.currentTrack ?: return
        val isFavorite = _uiState.value.isFavorite
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(track, !isFavorite)
        }
    }

    /** Intent: the playback error message was shown — clear it. */
    fun onErrorShown() {
        _playbackError.update { null }
    }

    /** Intent: user tapped play/pause. */
    fun onTogglePlayPause() {
        if (playbackController.state.value.isPlaying) {
            playbackController.pause()
        } else {
            playbackController.play()
        }
    }

    /** Intent: user dragged the seek bar. */
    fun onSeek(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    /**
     * Advance to the item after the current index and play it, skipping
     * unresolvable items. Never loops: when no playable item remains the queue
     * ends and the existing unavailable state is surfaced once.
     */
    private fun advanceFromNextIndex(userInitiated: Boolean) {
        val index = _uiState.value.currentIndex
        val next = (index ?: -1) + 1
        val queue = _uiState.value.queue
        if (next !in queue.indices) {
            // Boundary (skip at the end, ENDED past the last item): keep the
            // position on the final item — the player's ENDED status conveys
            // completion, and the track stays visible/favoritable.
            return
        }
        resolveJob?.cancel()
        _uiState.update { it.copy(currentIndex = next) }
        startLoadAtCurrentIndex()
    }

    /**
     * Resolve the track at the current index and load it. Unresolvable items
     * (metadata-only providers, network failures) are skipped forward; the
     * skip is surfaced once, not per item, to avoid snackbar spam. If no
     * playable item remains the queue stops gracefully.
     */
    private fun startLoadAtCurrentIndex() {
        val queue = _uiState.value.queue
        val startIndex = _uiState.value.currentIndex ?: return
        resolveJob?.cancel()
        isAdvancing = true
        resolveJob = viewModelScope.launch {
            var skipError: PlaybackError? = null
            for (index in startIndex until queue.size) {
                val track = queue[index]
                _uiState.update { it.copy(currentIndex = index) }
                val source = try {
                    libraryRepository.resolveSource(track)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    skipError = PlaybackError.UNRESOLVABLE
                    continue
                }
                if (source != null) {
                    isAdvancing = false
                    _playbackError.update { skipError }
                    playbackController.load(track, source)
                    return@launch
                }
                skipError = PlaybackError.UNAVAILABLE
            }
            // Every remaining item was unplayable: stop gracefully and
            // surface the failure exactly once, never loop. The index stays on
            // the unplayable item so the UI can still show it (and, e.g.,
            // favorite it) — no further advance happens without a new ENDED or
            // an explicit user skip.
            isAdvancing = false
            _playbackError.update { skipError ?: PlaybackError.UNAVAILABLE }
        }
    }

    override fun onCleared() {
        // Disconnects the MediaController; the service owns the actual player.
        playbackController.release()
        super.onCleared()
    }
}
