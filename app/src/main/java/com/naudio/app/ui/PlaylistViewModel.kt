package com.naudio.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naudio.core.model.Playlist
import com.naudio.core.model.Track
import com.naudio.data.repository.PlaylistRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Immutable UI state for the playlists section of the Library screen. */
data class PlaylistUiState(
    val playlists: List<Playlist> = emptyList(),
)

/** Immutable UI state for the playlist detail screen. */
data class PlaylistDetailUiState(
    /** The open playlist (with its live track count), or null when none/deleted. */
    val playlist: Playlist? = null,
    /** The playlist's tracks, in saved order (position ASC). */
    val tracks: List<Track> = emptyList(),
) {
    /** True when the playlist has no tracks yet (empty-state hint). */
    val isEmpty: Boolean
        get() = tracks.isEmpty()
}

/**
 * Unidirectional data flow for user playlists (M13): observes the playlist
 * repository (Room-backed) and exposes playlist-management intents for the
 * Library list, the playlist detail screen, and the add-to-playlist sheet.
 * Depends on the repository abstraction only — no DAO, no database types.
 * Playback is intentionally absent: playing a playlist reuses the existing
 * queue mechanism ([PlaybackViewModel.setQueue]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModel(
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    /** All playlists (newest first) with track counts. */
    val uiState: StateFlow<PlaylistUiState> = playlistRepository.observePlaylists()
        .map { PlaylistUiState(playlists = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PlaylistUiState(),
        )

    /** The playlist currently open in the detail screen, if any. */
    private val openPlaylistId = MutableStateFlow<Long?>(null)

    /**
     * Detail state for [openPlaylistId]: ordered tracks plus the playlist's
     * own metadata. Re-subscribes whenever another playlist is opened; the
     * track list updates live as tracks are added or removed.
     */
    val detailState: StateFlow<PlaylistDetailUiState> = openPlaylistId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(PlaylistDetailUiState())
            } else {
                combine(
                    playlistRepository.observePlaylist(id),
                    playlistRepository.observePlaylistTracks(id),
                ) { playlist, tracks ->
                    PlaylistDetailUiState(playlist = playlist, tracks = tracks)
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PlaylistDetailUiState(),
        )

    /** Intent: open a playlist in the detail screen. */
    fun openPlaylist(id: Long) {
        openPlaylistId.value = id
    }

    /** Intent: close the detail screen (Back). */
    fun closePlaylist() {
        openPlaylistId.value = null
    }

    /** Intent: create an empty playlist named [name] (blank names ignored). */
    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            playlistRepository.createPlaylist(trimmed)
        }
    }

    /** Intent: rename a playlist (blank names ignored). */
    fun renamePlaylist(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            playlistRepository.renamePlaylist(id, trimmed)
        }
    }

    /** Intent: delete a playlist (its membership rows go with it). */
    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            playlistRepository.deletePlaylist(id)
            if (openPlaylistId.value == id) openPlaylistId.value = null
        }
    }

    /** Intent: add [track] to the playlist with [playlistId] (at the end). */
    fun addTrackToPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch {
            playlistRepository.addTrackToPlaylist(playlistId, track)
        }
    }

    /** Intent: create a playlist named [name] and add [track] to it. */
    fun createPlaylistAndAddTrack(name: String, track: Track) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val playlistId = playlistRepository.createPlaylist(trimmed)
            playlistRepository.addTrackToPlaylist(playlistId, track)
        }
    }

    /** Intent: remove [track] from the playlist with [playlistId]. */
    fun removeTrack(playlistId: Long, track: Track) {
        viewModelScope.launch {
            playlistRepository.removeTrackFromPlaylist(
                playlistId = playlistId,
                providerId = track.providerId,
                trackId = track.id,
            )
        }
    }
}
