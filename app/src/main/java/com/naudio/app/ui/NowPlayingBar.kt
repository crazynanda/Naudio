package com.naudio.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naudio.core.model.Track

/**
 * Minimal now-playing strip rendered under the screens' content. Playback is
 * fully delegated via intents; this composable holds no player logic.
 * Controls: favorite (heart), previous, play/pause, next — plus the seek bar
 * and a buffering spinner while the player is loading media.
 */
@Composable
fun NowPlayingBar(
    track: Track?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipToNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (track == null) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Tapping the bar (outside the buttons/slider) opens the
            // full-screen player (M11).
            .clickable(onClick = onOpenPlayer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (durationMs > 0L) {
                Slider(
                    value = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                    onValueChange = { fraction -> onSeek((fraction * durationMs).toLong()) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // Favorite toggle: provider-aware identity lives in the ViewModel;
        // YTM (unplayable) tracks are favoritable like any other.
        TextButton(onClick = onToggleFavorite) {
            Text(if (isFavorite) "♥" else "♡")
        }
        TextButton(onClick = onSkipToPrevious) {
            Text("Prev")
        }
        TextButton(onClick = onTogglePlayPause) {
            Text(if (isPlaying) "Pause" else "Play")
        }
        TextButton(onClick = onSkipToNext) {
            Text("Next")
        }
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(20.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}
