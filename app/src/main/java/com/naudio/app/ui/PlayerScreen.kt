package com.naudio.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naudio.app.playback.PlaybackError
import com.naudio.core.player.PlayerState

/**
 * Full-screen player (M11/M12): real artwork with placeholder fallback,
 * metadata, position/duration, seek + transport controls, and the visual
 * playback queue. Consumes only ViewModel state and emits intents — no
 * repositories, no Media3, no player internals, no network clients (Coil
 * handles image loading inside [ArtworkImage]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    playerState: PlayerState,
    playbackUiState: PlaybackUiState,
    playbackError: PlaybackError?,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onJumpToQueueIndex: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onPlaybackErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
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
            TopAppBar(
                title = { Text("Now Playing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        // Fixed header (artwork/metadata/controls) + a height-bounded queue:
        // the LazyColumn below must never receive infinite max-height
        // constraints, so the screen itself does not verticalScroll — the
        // queue list owns the scrolling inside its weight(1f) slot.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ArtworkImage(
                artworkUrl = playerState.track?.artworkUrl,
                trackTitle = playerState.track?.title,
                contentDescription = "Artwork for " + (playerState.track?.title ?: "nothing playing"),
                isBuffering = playerState.isBuffering,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .fillMaxWidth()
                    .height(280.dp),
            )

            PlayerMetadata(
                title = playerState.track?.title.orEmpty(),
                artist = playerState.track?.artist.orEmpty(),
                isFavorite = playbackUiState.isFavorite,
                onToggleFavorite = onToggleFavorite,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            PlayerControls(
                isPlaying = playerState.isPlaying,
                positionMs = playerState.positionMs,
                durationMs = playerState.durationMs,
                onSkipToPrevious = onSkipToPrevious,
                onTogglePlayPause = onTogglePlayPause,
                onSkipToNext = onSkipToNext,
                onSeek = onSeek,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            PlayerQueue(
                queue = playbackUiState.queue,
                currentIndex = playbackUiState.currentIndex,
                onJumpToQueueIndex = onJumpToQueueIndex,
                onRemoveQueueItem = onRemoveQueueItem,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** Title + artist + favorite heart row for the full-screen player. */
@Composable
private fun PlayerMetadata(
    title: String,
    artist: String,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifEmpty { "Nothing playing" },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onToggleFavorite) {
            Text(if (isFavorite) "♥" else "♡")
        }
    }
}
