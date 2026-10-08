package com.naudio.app.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naudio.core.model.Playlist

/**
 * Reusable track-options sheet (M22): the actions that can be taken on a
 * track do not depend on where the track came from. The caller supplies the
 * intent callbacks, so the sheet is implemented once and reused by search,
 * artist, album, favourites and playlist surfaces without duplication.
 *
 * @param track the track whose options are shown.
 * @param playlists the playlists available for [onAddToPlaylist]; may be empty.
 * @param onPlayNext insert [track] after the current item (or establish a new
 *   queue if nothing is playing).
 * @param onAddToQueue append [track] to the end of the current queue.
 * @param onAddToPlaylist the chosen playlist id, if the user confirmed an
 *   add-to-playlist action. Null when the sheet was dismissed without the
 *   user selecting a playlist.
 * @param onDismiss the sheet was dismissed without choosing a playlist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun TrackOptionsSheet(
    track: com.naudio.core.model.Track,
    playlists: List<com.naudio.core.model.Playlist>,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            // Play next — insert after the currently playing item (or start a new queue).
            Button(
                onClick = onPlayNext,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(text = "Play next")
                }
            }

            Spacer(Modifier.height(8.dp))

            // Add to queue — append to the end of the current queue.
            Button(
                onClick = onAddToQueue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(text = "Add to queue")
                }
            }

            Spacer(Modifier.height(8.dp))

            // Add to playlist — present the playlist picker inside the sheet.
            if (playlists.isNotEmpty()) {
                Text(
                    text = "Add to playlist",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(4.dp))
                playlists.forEach { playlist ->
                    Button(
                        onClick = { onAddToPlaylist(playlist.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = playlist.name)
                    }
                }
            } else {
                Text(
                    text = "No playlists available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
