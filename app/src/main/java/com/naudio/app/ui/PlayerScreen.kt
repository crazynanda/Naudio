package com.naudio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naudio.app.playback.PlaybackError
import com.naudio.core.player.PlayerState
import com.naudio.core.player.PlaybackStatus
import kotlin.math.absoluteValue

/**
 * Full-screen player (M11): artwork/placeholder, metadata, position/duration,
 * seek + transport controls, and the visual playback queue. Consumes only
 * ViewModel state and emits intents — no repositories, no Media3, no player
 * internals. The queue section shares the screen via vertical scroll; no
 * second player-state mechanism exists.
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
            ArtworkPlaceholder(
                trackTitle = playerState.track?.title,
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

/**
 * Stable artwork placeholder: a deterministic gradient keyed on the title so
 * it does not flicker between recompositions. Graceful when no track is
 * loaded. No artwork downloading or caching architecture by design.
 */
/** Deterministic gradient pairs keyed by title hash — stable, no downloads. */
private val ArtworkPalettes = listOf(
    Color(0xFF5E35B1) to Color(0xFF9575CD),
    Color(0xFF1E88E5) to Color(0xFF64B5F6),
    Color(0xFF00897B) to Color(0xFF4DB6AC),
    Color(0xFFF4511E) to Color(0xFFFF8A65),
    Color(0xFF6D4C41) to Color(0xFFA1887F),
    Color(0xFF3949AB) to Color(0xFF7986CB),
)

@Composable
private fun ArtworkPlaceholder(
    trackTitle: String?,
    isBuffering: Boolean,
    modifier: Modifier = Modifier,
) {
    val seed = trackTitle?.hashCode()?.absoluteValue ?: 0
    val (base, accent) = ArtworkPalettes[seed % ArtworkPalettes.size]
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(listOf(base, accent)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isBuffering) {
                androidx.compose.material3.CircularProgressIndicator()
            } else {
                Text(
                    text = "♪",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 64.sp,
                )
            }
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
