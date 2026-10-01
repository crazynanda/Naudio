package com.naudio.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.naudio.app.playback.PlaybackError
import com.naudio.app.ui.theme.NaudioTheme
import com.naudio.core.model.Playlist
import com.naudio.core.model.Track
import com.naudio.core.player.PlayerState

/**
 * Library screen: user playlists (M13) on top, favorites below. Pure UDF
 * rendering — receives state, emits intents; tapping a favorite queues the
 * whole favorites list and starts playback at the tapped position, tapping a
 * playlist opens its detail screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    playlistState: PlaylistUiState,
    playerState: PlayerState,
    playbackError: PlaybackError?,
    isCurrentTrackFavorite: Boolean,
    onBack: () -> Unit,
    onTrackSelected: (index: Int) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenPlayer: () -> Unit,
    onPlaybackErrorShown: () -> Unit,
    onRemoveFavorite: (Track) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // System Back from Library returns Home; the visible Back button below
    // keeps its existing behavior.
    BackHandler(onBack = onBack)
    LaunchedEffect(playbackError) {
        if (playbackError != null) {
            snackbarHostState.showSnackbar(message = playbackError.message())
            onPlaybackErrorShown()
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Library") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
        bottomBar = {
            NowPlayingBar(
                track = playerState.track,
                isPlaying = playerState.isPlaying,
                isBuffering = playerState.isBuffering,
                positionMs = playerState.positionMs,
                durationMs = playerState.durationMs,
                isFavorite = isCurrentTrackFavorite,
                onToggleFavorite = onToggleFavorite,
                onSkipToPrevious = onSkipToPrevious,
                onTogglePlayPause = onTogglePlayPause,
                onSkipToNext = onSkipToNext,
                onSeek = onSeek,
                onOpenPlayer = onOpenPlayer,
            )
        },
    ) { innerPadding ->
        // M13: inline create-playlist dialog state.
        var showCreateDialog by remember { mutableStateOf(false) }
        var newPlaylistName by remember { mutableStateOf("") }
        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("New playlist") },
                text = {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        singleLine = true,
                        label = { Text("Playlist name") },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onCreatePlaylist(newPlaylistName)
                            newPlaylistName = ""
                            showCreateDialog = false
                        },
                        enabled = newPlaylistName.isNotBlank(),
                    ) { Text("Create") }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
                },
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ---- M13: playlists section ----
            item(key = "playlists-header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Playlists",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showCreateDialog = true }) { Text("New") }
                }
            }
            if (playlistState.playlists.isEmpty()) {
                item(key = "playlists-empty") {
                    Text(
                        text = "No playlists yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(playlistState.playlists, key = { "playlist-${it.id}" }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPlaylist(playlist.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = playlist.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${playlist.trackCount} tracks",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            // ---- Favorites section (pre-existing behavior, untouched) ----
            item(key = "favorites-header") {
                Text(
                    text = "Favorites",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (state.favorites.isEmpty()) {
                item(key = "favorites-empty") {
                    Text(
                        text = "Search for a track and tap ♡ to save it here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                itemsIndexed(state.favorites, key = { index, track -> "fav-$index-${track.providerId}-${track.id}" }) { index, track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onTrackSelected(index) },
                                onLongClick = { onRemoveFavorite(track) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // M12: artwork persisted with the favorite survives here.
                        ArtworkImage(
                            artworkUrl = track.artworkUrl,
                            trackTitle = track.title,
                            contentDescription = null,
                            cornerRadius = 6.dp,
                            glyphSize = 14.sp,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(44.dp),
                        )
                        Column {
                            Text(text = track.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LibraryScreenPreview() {
    NaudioTheme {
        LibraryScreen(
            state = LibraryUiState(
                favorites = listOf(
                    Track("1", "itunes", "Around the World", "Daft Punk"),
                    Track("2", "local", "Test Tone", "Naudio"),
                ),
            ),
            playlistState = PlaylistUiState(
                playlists = listOf(Playlist(id = 1, name = "Road trip", trackCount = 2)),
            ),
            playerState = PlayerState(),
            playbackError = null,
            isCurrentTrackFavorite = false,
            onBack = {},
            onTrackSelected = {},
            onTogglePlayPause = {},
            onSkipToNext = {},
            onSkipToPrevious = {},
            onToggleFavorite = {},
            onSeek = {},
            onOpenPlayer = {},
            onPlaybackErrorShown = {},
            onRemoveFavorite = {},
            onCreatePlaylist = {},
            onOpenPlaylist = {},
        )
    }
}
