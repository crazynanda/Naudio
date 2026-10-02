package com.naudio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Position/duration readout, seek bar, and transport controls for the
 * full-screen player. Stateless: receives values and emits intents, using the
 * existing player-state/ticker architecture (no second timing mechanism).
 */
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onSkipToPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipToNext: () -> Unit,
    onSeek: (Long) -> Unit,
    // M16: playback modes. Values come from PlayerState (the real Media3
    // state); this composable holds no shuffle/repeat state of its own.
    shuffleModeEnabled: Boolean = false,
    repeatMode: Int = 0,
    onToggleShuffle: () -> Unit = {},
    onCycleRepeatMode: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatTime(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (durationMs > 0L) {
            Slider(
                value = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                onValueChange = { fraction -> onSeek((fraction * durationMs).toLong()) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSkipToPrevious) { Text("Prev") }
            TextButton(onClick = onTogglePlayPause) {
                Text(if (isPlaying) "Pause" else "Play")
            }
            TextButton(onClick = onSkipToNext) { Text("Next") }
        }
        // M16: playback modes row, styled like the transport row above.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onToggleShuffle) {
                Text(
                    text = "Shuffle",
                    // Active state is the primary colour; inactive is the
                    // muted colour used by the rest of the player.
                    color = if (shuffleModeEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            TextButton(onClick = onCycleRepeatMode) {
                Text(
                    // Distinct label per mode, so the three states are
                    // distinguishable without relying on colour alone.
                    text = repeatLabel(repeatMode),
                    color = if (repeatMode == 0) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** Compact m:ss rendering shared by the player readouts. */
internal fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * M16: the repeat-mode label, kept next to the controls so the three states
 * stay in sync. Media3 constants are used rather than local magic numbers; an
 * unrecognised value is treated as "off".
 */
internal fun repeatLabel(repeatMode: Int): String = when (repeatMode) {
    1 -> "Repeat One"
    2 -> "Repeat All"
    else -> "Repeat"
}
