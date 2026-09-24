package com.naudio.app.ui

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
import com.naudio.core.model.Track

/**
 * Minimal now-playing strip rendered under the results list. Playback is
 * fully delegated via intents; this composable holds no player logic.
 */
@Composable
fun NowPlayingBar(
    track: Track?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (track == null) return

    Row(
        modifier = modifier
            .fillMaxWidth()
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
        TextButton(onClick = onTogglePlayPause) {
            Text(if (isPlaying) "Pause" else "Play")
        }
    }
}
