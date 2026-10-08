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
import com.naudio.app.ui.AlbumDetailScreen
import com.naudio.app.ui.TrackOptionsSheet
import com.naudio.app.ui.AlbumViewModel
import com.naudio.app.ui.ArtistDetailScreen
import com.naudio.app.ui.ArtistViewModel
import com.naudio.app.ui.CatalogDetailActions
import com.naudio.app.ui.CatalogDetailState
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
private enum class Screen { HOME, LIBRARY, PLAYLIST_DETAIL, PLAYER, ARTIST_DETAIL, ALBUM_DETAIL }

/**
 * The catalog entity a detail screen is showing (M21).
 *
 * Navigation passes this small value — a provider id plus a catalog id — never a
 * whole catalog object: the destination fetches its own data, so a screen
 * restored from saved state re-reads from the provider instead of trusting a
 * serialised snapshot. Both ids are the provider's OWN identifiers; nothing here
 * is derived from a display name.
 */
private data class CatalogTarget(val providerId: String, val catalogId: String)

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
            historyRepository = container.historyRepository,
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
    val lyricsViewModel: com.naudio.app.ui.LyricsViewModel = viewModel {
        com.naudio.app.ui.LyricsViewModel(
            playbackController = container.playbackController,
            lyricsRepository = container.lyricsRepository
        )
    }
    // M21 catalog detail. Activity-scoped like every other ViewModel here, so an
    // artist stays loaded while the player overlay opens on top of it.
    val artistViewModel: ArtistViewModel = viewModel {
        ArtistViewModel(libraryRepository = container.libraryRepository)
    }
    val albumViewModel: AlbumViewModel = viewModel {
        AlbumViewModel(libraryRepository = container.libraryRepository)
    }

    val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val playlistState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    val playlistDetailState by playlistViewModel.detailState.collectAsStateWithLifecycle()
    val playerState by playbackViewModel.playbackState.collectAsStateWithLifecycle()
    val playbackError by playbackViewModel.playbackError.collectAsStateWithLifecycle()
    val playbackUiState by playbackViewModel.uiState.collectAsStateWithLifecycle()
    val lyricsState by lyricsViewModel.lyricsState.collectAsStateWithLifecycle()
    val artistState by artistViewModel.state.collectAsStateWithLifecycle()
    val albumState by albumViewModel.state.collectAsStateWithLifecycle()
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

    // M21: catalog detail destinations. Each ViewModel owns the entity it is
    // showing and clears it on Back, mirroring how the playlist detail screen
    // clears its own open playlist. Only the ORIGIN screen is held here, so
    // system Back unwinds to wherever the detail page was opened from.
    var catalogOrigin by rememberSaveable { mutableStateOf(Screen.HOME) }
    var openAlbumContext by rememberSaveable { mutableStateOf<CatalogDetailActions.OpenAlbumContext?>(null) }
    fun closeCatalogDetail() {
        artistViewModel.closeArtist()
        albumViewModel.closeAlbum()
        screen = catalogOrigin
    }

    // The artist's entry point. The id is the provider's own — resolved through
    // [CatalogDetailActions.openArtist], which returns null unless the track
    // carries one — so this can only ever open a page the provider named.
    fun openArtistDetail(track: Track) {
        val target = CatalogDetailActions.openArtist(track) ?: return
        catalogOrigin = Screen.HOME
        artistViewModel.openArtist(target.providerId, target.catalogId)
        screen = Screen.ARTIST_DETAIL
    }

    // M21 Step 9 audit — where catalog navigation is wired, and where it is not.
    //
    // A track's artist and album are DISPLAY strings (`Track.artist`,
    // `Track.album`); the provider's own identity for those two entities now
    // arrives beside them (`Track.artistId`, `Track.albumId`) and is used only
    // where the provider actually sent it. `openArtistDetail` below is the entry
    // point: a search result's artist label is navigable exactly when that track
    // carries an artist id, and stays plain text otherwise — a name is never
    // used as an id, and no request is ever issued on a guessed one.
    //
    // Surfaces whose tracks come from a STORED copy — Player, Library favorites,
    // Playlist detail — still have no id, because persistence keeps the columns
    // it always had (M21 scope; changing the schema is deliberately out of
    // scope). They keep their plain labels.
    //
    // The one place a real album id existed before this is the discography row of
    // an artist page, whose cards come from the provider itself — so ALBUM_DETAIL
    // is reachable from ARTIST_DETAIL below too.

    // M13: the track queued for the add-to-playlist sheet (null = closed).
    var addToPlaylistTrack by remember { mutableStateOf<Track?>(null) }

    // M22: reusable track-options sheet, hosted on top of every screen that
    // knows about playlists (the full-screen player is the primary target).
    // Long-pressing a queue row opens this sheet with Play Next / Add to
    // Queue / Add to Playlist, reusing the existing playlist machinery.
    var trackOptionsTrack by remember { mutableStateOf<Track?>(null) }
    val openTrackOptions = { track: Track -> trackOptionsTrack = track }

    when (screen) {
        Screen.HOME -> HomeScreen(
            state = homeState,
            playerState = barPlayerState,
            playbackError = playbackError,
            isCurrentTrackFavorite = playbackUiState.isFavorite,
            onQueryChange = homeViewModel::onQueryChange,
            onRetry = homeViewModel::onRetry,
            onTrackSelected = playbackViewModel::onTrackSelected,
            // M17: a Recently Played item re-enters playback through the very
            // same single-item-queue entry point a search result uses.
            onHistoryEntrySelected = playbackViewModel::onTrackSelected,
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
            // M21 follow-up: a search result's artist label opens the provider's
            // own artist page — but only for a track that carries an artist id.
            onOpenArtist = ::openArtistDetail,
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

        // M21: catalog detail. The screens compute a [PlaybackRequest] through
        // [CatalogDetailActions] and this applies it via the EXISTING queue path —
        // setQueue(tracks, startIndex) on the shared PlaybackCoordinator — so the
        // M20 unplayable-track behaviour applies unchanged here and no second
        // playback path can grow.
        Screen.ARTIST_DETAIL -> ArtistDetailScreen(
            state = artistState,
            onBack = ::closeCatalogDetail,
            onPlayTopTracks = { tracks ->
                val request = CatalogDetailActions.playAll(tracks) ?: return@ArtistDetailScreen
                playbackViewModel.setQueue(request.tracks, request.startIndex)
                openPlayer()
            },
            onTrackSelected = { tracks, index ->
                CatalogDetailActions.selectTrack(tracks, index)?.let { request ->
                    playbackViewModel.setQueue(request.tracks, request.startIndex)
                }
            },
            // Step 9 guard, executed as code: a card without a real catalog id resolves to
            // null and the screen stays put, rather than inventing an id.
            onAlbumSelected = { album, artist ->
                val target = CatalogDetailActions.openAlbum(album) ?: return@ArtistDetailScreen
                // Remember the artist identity, not the album's: the album's `artist` string
                // is a display value only. Opening the album from an artist page returns to
                // the very artist that was open, preserving the provider ids it carries.
                openAlbumContext = CatalogDetailActions.OpenAlbumContext(
                    providerId = artist.providerId,
                    artistId = artist.id,
                )
                catalogOrigin = Screen.ARTIST_DETAIL
                albumViewModel.openAlbum(target.providerId, target.catalogId)
                screen = Screen.ALBUM_DETAIL
            },
        )

        Screen.ALBUM_DETAIL -> AlbumDetailScreen(
            state = albumState,
            onBack = {
                // Back from an album returns to the artist that was open when the album was
                // opened (if it was opened from an artist page), reusing that retrieved artist id —
                // never the album's display string. The album currently shown is what we just
                // closed, so the album→artist back step ignores its display string and uses the
                // identity captured at open time.
                val albumDetail = (albumState as? CatalogDetailState.Success)?.value
                if (albumDetail != null) {
                    val backTarget = CatalogDetailActions.backFromAlbum(openAlbumContext, albumDetail)
                    if (backTarget != null) {
                        catalogOrigin = Screen.ARTIST_DETAIL
                        artistViewModel.openArtist(backTarget.providerId, backTarget.artistId)
                        screen = Screen.ARTIST_DETAIL
                        return@AlbumDetailScreen
                    }
                }
                screen = catalogOrigin
            },
            // "Play Album" starts at index 0 so the release plays in order.
            onPlayAlbum = { tracks ->
                val request = CatalogDetailActions.playAll(tracks) ?: return@AlbumDetailScreen
                playbackViewModel.setQueue(request.tracks, request.startIndex)
                openPlayer()
            },
            onTrackSelected = { tracks, index ->
                CatalogDetailActions.selectTrack(tracks, index)?.let { request ->
                    playbackViewModel.setQueue(request.tracks, request.startIndex)
                }
            },
        )

        Screen.PLAYER -> PlayerScreen(
            playerState = playerState,
            playbackUiState = playbackUiState,
            playbackError = playbackError,
            lyricsState = lyricsState,
            onBack = ::closePlayer,
            onTogglePlayPause = playbackViewModel::onTogglePlayPause,
            onSkipToNext = playbackViewModel::skipToNext,
            onSkipToPrevious = playbackViewModel::skipToPrevious,
            onSeek = playbackViewModel::onSeek,
            onToggleFavorite = playbackViewModel::onToggleFavorite,
            onJumpToQueueIndex = playbackViewModel::jumpToQueueIndex,
            onRemoveQueueItem = playbackViewModel::removeQueueItem,
            onMoveQueueItem = playbackViewModel::moveQueueItem,
            onClearQueue = playbackViewModel::clearQueue,
            onTrackOptions = openTrackOptions,
            onPlaybackErrorShown = playbackViewModel::onErrorShown,
            onLyricsSeek = lyricsViewModel::onSeek,
            // M16: playback modes — commands go to the player, state comes
            // from PlayerState, so Auto and the mobile UI cannot diverge.
            onToggleShuffle = playbackViewModel::onToggleShuffle,
            onCycleRepeatMode = playbackViewModel::onCycleRepeatMode,
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

    // M22: track-options sheet, hosted above the player (only meaningful when
    // the player is open, since long-pressing is only wired in the queue view).
    trackOptionsTrack?.let { track ->
        TrackOptionsSheet(
            track = track,
            playlists = playlistState.playlists,
            onPlayNext = {
                playbackViewModel.playNext(track)
                trackOptionsTrack = null
            },
            onAddToQueue = {
                playbackViewModel.addToQueue(listOf(track))
                trackOptionsTrack = null
            },
            onAddToPlaylist = { playlistId ->
                if (playlistId != null) {
                    playlistViewModel.addTrackToPlaylist(playlistId, track)
                }
                trackOptionsTrack = null
            },
            onDismiss = { trackOptionsTrack = null },
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
