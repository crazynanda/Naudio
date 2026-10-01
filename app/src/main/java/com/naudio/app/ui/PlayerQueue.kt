package com.naudio.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naudio.core.model.Track

/**
 * Visual playback queue (M11) for the full-screen player: scrollable list with
 * the current item highlighted, tap-to-jump and per-item remove. No drag
 * reordering; all playback actions are intents only.
 */
@Composable
fun PlayerQueue(
    queue: List<Track>,
    currentIndex: Int?,
    onJumpToQueueIndex: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Queue (" + queue.size + ")",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        if (queue.isEmpty()) {
            Text(
                text = "Queue is empty.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Positional identity: provider ids may repeat inside a queue.
                itemsIndexed(queue) { index, track ->
                    QueueItem(
                        position = index + 1,
                        track = track,
                        isCurrent = index == currentIndex,
                        onClick = { onJumpToQueueIndex(index) },
                        onRemove = { onRemoveQueueItem(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueItem(
    position: Int,
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isCurrent) {
            Text(
                text = "▶",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(
                text = position.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        // M12: small artwork thumbnail; deterministic placeholder fallback.
        ArtworkImage(
            artworkUrl = track.artworkUrl,
            trackTitle = track.title,
            contentDescription = null,
            cornerRadius = 6.dp,
            glyphSize = 14.sp,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(44.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Remove " + track.title,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
