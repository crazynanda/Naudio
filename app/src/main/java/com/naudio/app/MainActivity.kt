package com.naudio.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.naudio.app.di.AppContainer
import com.naudio.app.ui.AddToPlaylistSheet
import com.naudio.app.ui.HomeScreen
import com.naudio.app.ui.HomeViewModel
import com.naudio.app.ui.LibraryScreen
import com.naudio.app.ui.LibraryViewModel
import com.naudio.app.ui.PlaybackViewModel
import com.naudio.app.ui.PlaylistDetailScreen
import com.naudio.app.ui.PlaylistViewModel
import com.naudio.app.ui.PlayerScreen
import com.naudio.app.ui.theme.NaudioTheme
import com.naudio.core.model.Track

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NaudioApplication).container
        setContent {
            NaudioTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NaudioRoute(container = container)
                }
            }
        }
    }
}

/** The screens of the app; navigation is a simple state switch. */
private enum class Screen { HOME, LIBRARY, PLAYLIST_DETAIL, PLAYER }

/**
 * Minimal state-based navigation (no Navigation Compose — the app has exactly
 * four screens and the existing architecture is a single-activity Compose
 * route). ViewModels are activity-scoped so playback (and the persistent
 * queue) survives switching screens.
 */
@Composable
private fun NaudioRoute(container: AppContainer) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    // The full-screen player is an overlay on top of HOME or LIBRARY; the
    // screen it was opened from is remembered so system Back returns there.
    var playerOrigin by rememberSaveable { mutableStateOf(Screen.HOME) }

    val homeViewModel: HomeViewModel = viewModel {
        HomeViewModel(
            repository = container.libraryRepository,
            registry = container.providerRegistry,
        )
    }
    val libraryViewModel: LibraryViewModel = viewModel {
        LibraryViewModel(
            favoritesRepository = container.favoritesRepository,
        )
    }
    val playlistViewModel: PlaylistViewModel = viewModel {
        PlaylistViewModel(
            playlistRepository = container.playlistRepository,
        )
    }
    val playbackViewModel: PlaybackViewModel = viewModel {
        PlaybackViewModel(
            playbackController = container.playbackController,
            libraryRepository = container.libraryRepository,
            favoritesRepository = container.favoritesRepository,
            queueRepository = container.queueRepository,
            sharedCoordinator = container.playbackCoordinator,
        )
    }

    val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val playlistState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    val playlistDetailState by playlistViewModel.detailState.collectAsStateWithLifecycle()
    val playerState by playbackViewModel.playbackState.collectAsStateWithLifecycle()
    val playbackError by playbackViewModel.playbackError.collectAsStateWithLifecycle()
    val playbackUiState by playbackViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The now-playing bar shows the queue's current track — even when that
    // item could not be resolved (e.g. a YouTube Music favorite), so it stays
    // visible and favoritable — falling back to the player's loaded track.
    val barTrack = playbackUiState.currentTrack ?: playerState.track
    val barPlayerState = if (barTrack != playerState.track) {
        playerState.copy(track = barTrack)
    } else {
        playerState
    }

    // Lifecycle-safe media permission state: held in compose state, refreshed
    // when the composable re-enters composition (user may grant in Settings),
    // requested via the ActivityResult API only when the user asks — never per
    // recomposition.
    var audioPermissionGranted by remember {
        mutableStateOf(context.hasAudioPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { audioPermissionGranted = it || context.hasAudioPermission() }

    fun openPlayer() {
        // Remember where the player was opened from so Back returns there.
        if (screen != Screen.PLAYER) playerOrigin = screen
        screen = Screen.PLAYER
    }
    fun closePlayer() {
        screen = playerOrigin
    }

    // System Back inside the player returns to the previous screen; Library
    // already handles its own Back to Home.
    BackHandler(enabled = screen == Screen.PLAYER) { closePlayer() }

    // M13: the track queued for the add-to-playlist sheet (null = closed).
    var addToPlaylistTrack by remember { mutableStateOf<Track?>(null) }

    when (screen) {
        Screen.HOME -> HomeScreen(
            state = homeState,
            playerState = barPlayerState,
            playbackError = playbackError,
            isCurrentTrackFavorite = playbackUiState.isFavorite,
            onQueryChange = homeViewModel::onQueryChange,
            onRetry = homeViewModel::onRetry,
            onTrackSelected = playbackViewModel::onTrackSelected,
            onTogglePlayPause = playbackViewModel::onTogglePlayPause,
            onSkipToNext = playbackViewModel::skipToNext,
            onSkipToPrevious = playbackViewModel::skipToPrevious,
            onToggleFavorite = playbackViewModel::onToggleFavorite,
            onSeek = playbackViewModel::onSeek,
            onPlaybackErrorShown = playbackViewModel::onErrorShown,
            audioPermissionGranted = audioPermissionGranted,
            onRequestAudioPermission = {
                permissionLauncher.launch(audioPermission())
            },
            onSelectProvider = homeViewModel::onSelectProvider,
            onOpenLibrary = { screen = Screen.LIBRARY },
            onOpenPlayer = ::openPlayer,
            // M13: long-press a search result to add it to a playlist.
            onAddToPlaylist = { track -> addToPlaylistTrack = track },
        )

        Screen.LIBRARY -> LibraryScreen(
            state = libraryState,
            playlistState = playlistState,
            playerState = barPlayerState,
            playbackError = playbackError,
            isCurrentTrackFavorite = playbackUiState.isFavorite,
            onBack = { screen = Screen.HOME },
            // Tapping a favorite queues the whole favorites list and starts
            // at the tapped position.
            onTrackSelected = { index ->
                playbackViewModel.onLibraryTrackSelected(libraryState.favorites, index)
            },
            onTogglePlayPause = playbackViewModel::onTogglePlayPause,
            onSkipToNext = playbackViewModel::skipToNext,
            onSkipToPrevious = playbackViewModel::skipToPrevious,
            onToggleFavorite = playbackViewModel::onToggleFavorite,
            onSeek = playbackViewModel::onSeek,
            onOpenPlayer = ::openPlayer,
            onPlaybackErrorShown = playbackViewModel::onErrorShown,
            // Long-press a favorite to remove it (provider-aware identity).
            onRemoveFavorite = libraryViewModel::onRemoveFavorite,
            // M13: playlists.
            onCreatePlaylist = playlistViewModel::createPlaylist,
            onOpenPlaylist = { id ->
                playlistViewModel.openPlaylist(id)
                screen = Screen.PLAYLIST_DETAIL
            },
        )

        Screen.PLAYLIST_DETAIL -> PlaylistDetailScreen(
            state = playlistDetailState,
            playerState = barPlayerState,
            playbackError = playbackError,
            isCurrentTrackFavorite = playbackUiState.isFavorite,
            onBack = {
                playlistViewModel.closePlaylist()
                screen = Screen.LIBRARY
            },
            // Playing a playlist reuses the existing queue mechanism: the
            // ordered tracks are pushed through setQueue and the
            // PlaybackCoordinator/Media3 stack plays them (no playlist-specific
            // playback path).
            onPlayPlaylist = {
                playbackViewModel.setQueue(
                    tracks = playlistDetailState.tracks,
                    startIndex = 0,
                )
                openPlayer()
            },
            onTrackSelected = { index ->
                playbackViewModel.setQueue(
                    tracks = playlistDetailState.tracks,
                    startIndex = index,
                )
            },
            onRemoveTrack = { track ->
                playlistDetailState.playlist?.let { playlist ->
                    playlistViewModel.removeTrack(playlist.id, track)
                }
            },
            onRenamePlaylist = { name ->
                playlistDetailState.playlist?.let { playlist ->
                    playlistViewModel.renamePlaylist(playlist.id, name)
                }
            },
            onDeletePlaylist = {
                playlistDetailState.playlist?.let { playlist ->
                    playlistViewModel.deletePlaylist(playlist.id)
                }
            },
            onTogglePlayPause = playbackViewModel::onTogglePlayPause,
            onSkipToNext = playbackViewModel::skipToNext,
            onSkipToPrevious = playbackViewModel::skipToPrevious,
            onToggleFavorite = playbackViewModel::onToggleFavorite,
            onSeek = playbackViewModel::onSeek,
            onOpenPlayer = ::openPlayer,
            onPlaybackErrorShown = playbackViewModel::onErrorShown,
        )

        Screen.PLAYER -> PlayerScreen(
            playerState = playerState,
            playbackUiState = playbackUiState,
            playbackError = playbackError,
            onBack = ::closePlayer,
            onTogglePlayPause = playbackViewModel::onTogglePlayPause,
            onSkipToNext = playbackViewModel::skipToNext,
            onSkipToPrevious = playbackViewModel::skipToPrevious,
            onSeek = playbackViewModel::onSeek,
            onToggleFavorite = playbackViewModel::onToggleFavorite,
            onJumpToQueueIndex = playbackViewModel::jumpToQueueIndex,
            onRemoveQueueItem = playbackViewModel::removeQueueItem,
            onPlaybackErrorShown = playbackViewModel::onErrorShown,
        )
    }

    // M13: add-to-playlist sheet, hosted above every screen (Home long-press
    // and Library entry points both feed it).
    addToPlaylistTrack?.let { track ->
        AddToPlaylistSheet(
            playlists = playlistState.playlists,
            track = track,
            onDismiss = { addToPlaylistTrack = null },
            onAddToPlaylist = { playlist ->
                playlistViewModel.addTrackToPlaylist(playlist.id, track)
                addToPlaylistTrack = null
            },
            onCreatePlaylistAndAdd = { name ->
                playlistViewModel.createPlaylistAndAddTrack(name, track)
                addToPlaylistTrack = null
            },
        )
    }
}

/** The media-read permission for this OS version (13+ uses the audio-scoped one). */
private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun android.content.Context.hasAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
    this,
    audioPermission(),
) == PackageManager.PERMISSION_GRANTED
