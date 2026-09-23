package com.naudio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naudio.app.ui.theme.NaudioTheme
import com.naudio.core.model.Track
import com.naudio.data.repository.LibraryQueryState

/**
 * Stateless home screen. Receives state + emits intents — pure UDF rendering.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Naudio") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
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

            when (val search = state.searchState) {
                is LibraryQueryState.Idle -> IdleHint()
                is LibraryQueryState.Loading -> LoadingIndicator()
                is LibraryQueryState.Results -> ResultsList(search.tracks)
                is LibraryQueryState.Error -> ErrorPane(message = search.message, onRetry = onRetry)
            }
        }
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
private fun ResultsList(tracks: List<Track>) {
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
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
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
                searchState = LibraryQueryState.Idle,
            ),
            onQueryChange = {},
            onRetry = {},
        )
    }
}
