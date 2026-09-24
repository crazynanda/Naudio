package com.naudio.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naudio.core.model.Track
import com.naudio.core.player.PlaybackController
import com.naudio.core.player.PlayerState
import com.naudio.data.repository.LibraryRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Unidirectional data flow for playback: intents in ([onTrackSelected],
 * [onTogglePlayPause], [onSeek]), state out ([playbackState]). Delegates all
 * playback to [PlaybackController]; never references Media3 or ExoPlayer.
 */
class PlaybackViewModel(
    private val playbackController: PlaybackController,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    val playbackState: StateFlow<PlayerState> = playbackController.state

    /** Intent: user tapped a track — resolve its source, then load and play. */
    fun onTrackSelected(track: Track) {
        viewModelScope.launch {
            val source = libraryRepository.resolveSource(track) ?: return@launch
            playbackController.load(track, source)
        }
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

    override fun onCleared() {
        // Disconnects the MediaController; the service owns the actual player.
        playbackController.release()
        super.onCleared()
    }
}
