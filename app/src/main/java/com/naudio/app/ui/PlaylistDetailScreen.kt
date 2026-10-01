package com.naudio.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
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
 * Detail screen for one user playlist (M13): the ordered track list
 * (position ASC), playlist playback through the existing queue mechanism,
 * per-track removal (long-press), rename, and delete. Pure UDF rendering —
 * receives state, emits intents; it holds no playlist or playback logic.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    state: PlaylistDetailUiState,
    playerState: PlayerState,
    playbackError: PlaybackError?,
    isCurrentTrackFavorite: Boolean,
    onBack: () -> Unit,
    onPlayPlaylist: () -> Unit,
    onTrackSelected: (index: Int) -> Unit,
    onRemoveTrack: (Track) -> Unit,
    onRenamePlaylist: (String) -> Unit,
    onDeletePlaylist: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenPlayer: () -> Unit,
    onPlaybackErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // M13: inline rename dialog state.
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
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
                title = { Text(state.playlist?.name ?: "Playlist") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            renameValue = state.playlist?.name.orEmpty()
                            showRenameDialog = true
                        },
                        enabled = state.playlist != null,
                    ) {
                        Text("Rename")
                    }
                    TextButton(onClick = onDeletePlaylist, enabled = state.playlist != null) {
                        Text("Delete")
                    }
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
        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename playlist") },
                text = {
                    OutlinedTextField(
                        value = renameValue,
                        onValueChange = { renameValue = it },
                        singleLine = true,
                        label = { Text("Playlist name") },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onRenamePlaylist(renameValue)
                            showRenameDialog = false
                        },
                        enabled = renameValue.isNotBlank(),
                    ) { Text("Rename") }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
                },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${state.tracks.size} tracks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onPlayPlaylist,
                    enabled = state.tracks.isNotEmpty(),
                ) {
                    Text("Play all")
                }
            }

            if (state.isEmpty) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("This playlist is empty.", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Search for a track and use “Add to playlist” to fill it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(state.tracks) { index, track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onTrackSelected(index) },
                                    onLongClick = { onRemoveTrack(track) },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(width = 20.dp, height = 44.dp),
                            )
                            // M12 artwork: the persisted metadata renders here too.
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
                            Column(modifier = Modifier.weight(1f)) {
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
}

@Preview(showBackground = true)
@Composable
private fun PlaylistDetailScreenPreview() {
    NaudioTheme {
        PlaylistDetailScreen(
            state = PlaylistDetailUiState(
                playlist = Playlist(id = 1, name = "Road trip", trackCount = 2),
                tracks = listOf(
                    Track("1", "itunes", "Around the World", "Daft Punk"),
                    Track("2", "local", "Test Tone", "Naudio"),
                ),
            ),
            playerState = PlayerState(),
            playbackError = null,
            isCurrentTrackFavorite = false,
            onBack = {},
            onPlayPlaylist = {},
            onTrackSelected = {},
            onRemoveTrack = {},
            onRenamePlaylist = {},
            onDeletePlaylist = {},
            onTogglePlayPause = {},
            onSkipToNext = {},
            onSkipToPrevious = {},
            onToggleFavorite = {},
            onSeek = {},
            onOpenPlayer = {},
            onPlaybackErrorShown = {},
        )
    }
}
