package com.naudio.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.naudio.app.playback.PlaybackError
import com.naudio.app.ui.theme.NaudioTheme
import com.naudio.core.model.Track
import com.naudio.core.player.PlayerState

/**
 * Minimal Library screen: the user's favorited tracks. Pure UDF rendering —
 * receives state, emits intents; tapping a track queues the whole favorites
 * list and starts playback at the tapped position.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
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
        if (state.isEmpty) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No favorites yet.", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Search for a track and tap ♡ to save it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(state.favorites) { index, track ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onTrackSelected(index) },
                                onLongClick = { onRemoveFavorite(track) },
                            )
                            .padding(vertical = 8.dp),
                    ) {
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
        )
    }
}
