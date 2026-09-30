package com.naudio.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naudio.app.playback.PlaybackCoordinator
import com.naudio.app.playback.PlaybackError
import com.naudio.core.model.Track
import com.naudio.core.player.PlaybackController
import com.naudio.core.player.PlayerState
import com.naudio.data.repository.FavoritesRepository
import com.naudio.data.repository.LibraryRepository
import com.naudio.data.repository.QueueRepository
import com.naudio.data.repository.TrackKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Snapshot of the playback queue and favorite state for the UI. Queue state is
 * owned by [PlaybackCoordinator] and mapped here; favorite identities come
 * from [FavoritesRepository].
 */
data class PlaybackUiState(
    /** Playback queue, in playback order. Empty when nothing is queued. */
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
 * Unidirectional data flow for playback (M10): intents in, state out. The
 * ViewModel is primarily a facade — it delegates queue orchestration (queue
 * state, index, next/previous, auto-advance, just-in-time resolution,
 * unplayable skipping, persistence) to [PlaybackCoordinator], and keeps only
 * UI glue: intent delegation, favorites integration, and UI state mapping.
 * It never references Media3 or ExoPlayer.
 */
class PlaybackViewModel(
    private val playbackController: PlaybackController,
    libraryRepository: LibraryRepository,
    private val favoritesRepository: FavoritesRepository,
    queueRepository: QueueRepository,
) : ViewModel() {

    val playbackState: StateFlow<PlayerState> = playbackController.state

    private val coordinator = PlaybackCoordinator(
        playbackController = playbackController,
        libraryRepository = libraryRepository,
        queueRepository = queueRepository,
        scope = viewModelScope,
    )

    /** One-shot playback error; cleared via [onErrorShown]. */
    val playbackError: StateFlow<PlaybackError?> = coordinator.state
        .map { it.error }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Queue position + derived favorite state for the now-playing UI. */
    val uiState: StateFlow<PlaybackUiState> =
        combine(coordinator.state, favoritesRepository.observeFavoriteIds()) { queueState, ids ->
            PlaybackUiState(
                queue = queueState.queue,
                currentIndex = queueState.currentIndex,
                favoriteIds = ids,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackUiState())

    /** Intent: user tapped a track — play it as a single-item queue. */
    fun onTrackSelected(track: Track) {
        coordinator.setQueue(listOf(track), startIndex = 0)
    }

    /**
     * Intent: the user tapped the Library favorite at [index] — enqueue the
     * whole favorites list and start playback at that position.
     */
    fun onLibraryTrackSelected(favorites: List<Track>, index: Int) {
        coordinator.setQueue(tracks = favorites, startIndex = index)
    }

    /**
     * Intent: replace the queue with [tracks] and start at [startIndex].
     * An empty queue or an out-of-range index is ignored (no state change,
     * no crash); playback of the previously loaded item is untouched.
     */
    fun setQueue(tracks: List<Track>, startIndex: Int) {
        coordinator.setQueue(tracks = tracks, startIndex = startIndex)
    }

    /**
     * Intent: jump to the next queue item. At the final item there is nothing
     * to advance to, so the request is a safe no-op.
     */
    fun skipToNext() {
        coordinator.skipToNext()
    }

    /**
     * Intent: jump to the previous queue item. Before the first item the
     * current track is restarted (resumed, or first loaded after a restore)
     * instead of crashing.
     */
    fun skipToPrevious() {
        coordinator.skipToPrevious()
    }

    /**
     * Intent (M11 queue UI): jump directly to the queue item at [index].
     * Invalid indexes and jumps to the current item are safe no-ops that
     * never reload the track.
     */
    fun jumpToQueueIndex(index: Int) {
        coordinator.jumpToQueueIndex(index)
    }

    /**
     * Intent (M11 queue UI): remove the queue item at [index] persistently.
     * Removal never restarts the current track unless the current item itself
     * was removed; invalid indexes are safe no-ops.
     */
    fun removeQueueItem(index: Int) {
        coordinator.removeQueueItem(index)
    }

    /** Intent: toggle the favorite state of the current track. */
    fun onToggleFavorite() {
        val track = coordinator.state.value.currentTrack ?: return
        val isFavorite = uiState.value.isFavorite
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(track, !isFavorite)
        }
    }

    /** Intent: the playback error message was shown — clear it. */
    fun onErrorShown() {
        coordinator.onErrorShown()
    }

    /**
     * Intent: user tapped play/pause. Delegated to the coordinator, which
     * resumes the restored current item on the very first Play press after a
     * relaunch.
     */
    fun onTogglePlayPause() {
        coordinator.onTogglePlayPause()
    }

    /** Intent: user dragged the seek bar. */
    fun onSeek(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    override fun onCleared() {
        // Disconnects the MediaController; the service owns the actual player.
        // The coordinator has no lifecycle of its own — it runs on the
        // viewModelScope, so its jobs are cancelled automatically.
        playbackController.release()
        super.onCleared()
    }
}
