package com.naudio.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naudio.core.model.Lyrics
import com.naudio.core.model.Track
import com.naudio.core.player.PlaybackController
import com.naudio.data.repository.LyricsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Manages fetching and presenting lyrics for the currently playing track.
 * Observes the authoritative player state and fetches lyrics automatically on track change.
 */
class LyricsViewModel(
    private val playbackController: PlaybackController,
    private val lyricsRepository: LyricsRepository
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val lyricsState: StateFlow<LyricsState> = playbackController.state
        .map { it.track }
        .flatMapLatest { track ->
            flow {
                if (track == null) {
                    emit(LyricsState.Idle)
                    return@flow
                }
                emit(LyricsState.Loading(track))
                try {
                    val lyrics = lyricsRepository.getLyrics(track)
                    emit(LyricsState.Success(track, lyrics))
                } catch (e: Exception) {
                    // Only network errors that were not caught might surface here,
                    // but the repository handles it and returns Unavailable or throws Cancellation.
                    emit(LyricsState.Success(track, Lyrics.Unavailable))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, LyricsState.Idle)

    /**
     * Intent: jump to a specific lyric line's start time.
     */
    fun onSeek(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }
}

sealed interface LyricsState {
    data object Idle : LyricsState
    data class Loading(val track: Track) : LyricsState
    data class Success(val track: Track, val lyrics: Lyrics) : LyricsState
}
