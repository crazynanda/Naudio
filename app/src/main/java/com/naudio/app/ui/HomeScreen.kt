package com.naudio.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naudio.app.ui.theme.NaudioTheme
import com.naudio.core.model.Track
import com.naudio.core.player.PlayerState
import com.naudio.data.repository.LibraryQueryState

/**
 * Stateless home screen. Receives state + emits intents — pure UDF rendering.
 * Playback surfaces (selection, now-playing strip) are delegated via intents;
 * this file holds no player logic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    playerState: PlayerState,
    playbackError: PlaybackError?,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onTrackSelected: (Track) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlaybackErrorShown: () -> Unit,
    audioPermissionGranted: Boolean,
    onRequestAudioPermission: () -> Unit,
    onSelectProvider: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Playback-resolution failures (no playback provider for the track, or the
    // provider could not resolve it) surface as a one-shot snackbar.
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
                title = { Text("Naudio") },
            )
        },
        bottomBar = {
            NowPlayingBar(
                track = playerState.track,
                isPlaying = playerState.isPlaying,
                isBuffering = playerState.isBuffering,
                positionMs = playerState.positionMs,
                durationMs = playerState.durationMs,
                onTogglePlayPause = onTogglePlayPause,
                onSeek = onSeek,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ProviderSelector(
                providers = state.providers,
                activeProviderId = state.activeProviderId,
                onSelectProvider = onSelectProvider,
            )

            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                singleLine = true,
                placeholder = { Text("Search your library") },
            )

            Text(
                text = state.providerName?.let { "Provider: $it" } ?: "No provider active",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(16.dp),
            )

            when {
                !audioPermissionGranted -> PermissionRequiredPane(onRequestAudioPermission)
                else -> when (val search = state.searchState) {
                    is LibraryQueryState.Idle -> IdleHint()
                    is LibraryQueryState.Loading -> LoadingIndicator()
                    is LibraryQueryState.Results ->
                        ResultsList(tracks = search.tracks, onTrackSelected = onTrackSelected)
                    is LibraryQueryState.Error -> ErrorPane(message = search.message, onRetry = onRetry)
                }
            }
        }
    }
}

/**
 * Minimal provider selector: a TextButton + DropdownMenu next to the provider
 * label. No provider-management architecture — just activating a ProviderId.
 */
@Composable
private fun ProviderSelector(
    providers: List<ProviderOption>,
    activeProviderId: String?,
    onSelectProvider: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Provider:",
            style = MaterialTheme.typography.labelMedium,
        )
        TextButton(onClick = { expanded = true }) {
            Text(
                text = providers.firstOrNull { it.id == activeProviderId }?.displayName
                    ?: "Select provider",
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = {
                        Text(if (provider.id == activeProviderId) "● ${provider.displayName}" else provider.displayName)
                    },
                    onClick = {
                        expanded = false
                        onSelectProvider(provider.id)
                    },
                )
            }
        }
    }
}

/** Shown when media-read permission is missing; the provider is never queried. */
@Composable
private fun PermissionRequiredPane(onRequestAudioPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Audio permission is needed to search this device's music library.",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onRequestAudioPermission) { Text("Grant audio permission") }
    }
}

@Composable
private fun IdleHint() {
    Text(
        text = "Type to search the local library.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun LoadingIndicator() {
    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
}

@Composable
private fun ResultsList(
    tracks: List<Track>,
    onTrackSelected: (Track) -> Unit,
) {
    if (tracks.isEmpty()) {
        Text(
            text = "No results.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(tracks, key = { it.id }) { track ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTrackSelected(track) }
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

/** User-facing text for a playback-resolution error. */
fun PlaybackError.message(): String = when (this) {
    PlaybackError.UNAVAILABLE -> "Playback isn't available for this item."
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    NaudioTheme {
        HomeScreen(
            state = HomeUiState(
                query = "",
                providerName = "Local library",
                providers = listOf(ProviderOption("local", "Local library")),
                activeProviderId = "local",
                searchState = LibraryQueryState.Idle,
            ),
            playerState = PlayerState(),
            playbackError = null,
            onQueryChange = {},
            onRetry = {},
            onTrackSelected = {},
            onTogglePlayPause = {},
            onSeek = {},
            onPlaybackErrorShown = {},
            audioPermissionGranted = true,
            onRequestAudioPermission = {},
            onSelectProvider = {},
        )
    }
}
