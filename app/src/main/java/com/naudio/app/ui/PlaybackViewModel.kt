package com.naudio.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naudio.core.model.Track
import com.naudio.core.player.PlaybackController
import com.naudio.core.player.PlayerState
import com.naudio.data.repository.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** User-visible reason the last playback request could not start. */
enum class PlaybackError {
    /** No playback provider is registered for the track's provider — e.g. YouTube Music. */
    UNAVAILABLE,
}

/**
 * Unidirectional data flow for playback: intents in ([onTrackSelected],
 * [onTogglePlayPause], [onSeek], [onErrorShown]), state out ([playbackState],
 * [playbackError]). Delegates all playback to [PlaybackController]; never
 * references Media3 or ExoPlayer.
 */
class PlaybackViewModel(
    private val playbackController: PlaybackController,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    val playbackState: StateFlow<PlayerState> = playbackController.state

    private val _playbackError = MutableStateFlow<PlaybackError?>(null)

    /** One-shot playback-resolution error; cleared via [onErrorShown]. */
    val playbackError: StateFlow<PlaybackError?> = _playbackError.asStateFlow()

    /** In-flight source resolution, cancelled by a newer selection. */
    private var resolveJob: Job? = null

    /** Intent: user tapped a track — resolve its source, then load and play. */
    fun onTrackSelected(track: Track) {
        // A newer selection supersedes the previous resolution attempt.
        resolveJob?.cancel()
        _playbackError.update { null }
        resolveJob = viewModelScope.launch {
            val source = try {
                libraryRepository.resolveSource(track)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                // Provider/network failures during resolution surface as a
                // user-visible playback error, never a crash or silent no-op.
                _playbackError.update { PlaybackError.UNAVAILABLE }
                return@launch
            }
            if (source == null) {
                // Metadata-only provider (e.g. YouTube Music) or a provider
                // that cannot serve this track: tell the user instead of
                // silently doing nothing.
                _playbackError.update { PlaybackError.UNAVAILABLE }
                return@launch
            }
            playbackController.load(track, source)
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

    override fun onCleared() {
        // Disconnects the MediaController; the service owns the actual player.
        playbackController.release()
        super.onCleared()
    }
}
