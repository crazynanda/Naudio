package com.naudio.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naudio.app.playback.PlaybackError
import com.naudio.app.ui.theme.NaudioTheme
import com.naudio.core.model.CatalogAlbumSummary
import com.naudio.core.model.CatalogArtistSummary
import com.naudio.core.model.History
import com.naudio.core.model.SearchCategory
import com.naudio.core.model.Track
import com.naudio.core.player.PlayerState
import com.naudio.data.repository.CatalogQueryState
import com.naudio.data.repository.CatalogSearchResult

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
    isCurrentTrackFavorite: Boolean,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onTrackSelected: (Track) -> Unit,
    onHistoryEntrySelected: (Track) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenPlayer: () -> Unit,
    onPlaybackErrorShown: () -> Unit,
    audioPermissionGranted: Boolean,
    onRequestAudioPermission: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onSelectCategory: (SearchCategory) -> Unit,
    onOpenLibrary: () -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onTrackOptions: (Track) -> Unit,
    onOpenArtist: (Track) -> Unit,
    onOpenArtistCard: (CatalogArtistSummary) -> Unit,
    onOpenAlbumCard: (CatalogAlbumSummary) -> Unit,
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
            CenterAlignedTopAppBar(
                title = { Text("Naudio") },
                actions = {
                    TextButton(onClick = onOpenLibrary) { Text("Library") }
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

            // M23: category chips drive [state.category], which re-enters the
            // search pipeline. Tapping one switches the visible results section.
            CategoryChips(
                category = state.category,
                onCategorySelected = onSelectCategory,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Text(
                text = state.providerName?.let { "Provider: $it" } ?: "No provider active",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // M17: Recently Played. Hidden entirely while the log is empty.
            if (state.recentlyPlayed.isNotEmpty()) {
                RecentlyPlayedRow(
                    entries = state.recentlyPlayed,
                    onEntrySelected = onHistoryEntrySelected,
                )
            }

            renderCatalogResults(
                state = state.searchState,
                onTrackSelected = onTrackSelected,
                onAddToPlaylist = onAddToPlaylist,
                onTrackOptions = onTrackOptions,
                onOpenArtist = onOpenArtist,
                onOpenArtistCard = onOpenArtistCard,
                onOpenAlbumCard = onOpenAlbumCard,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Category chips. Tapping one emits the category the search pipeline
 * observes, so the visible results switch section immediately.
 */
@Composable
fun CategoryChips(
    category: SearchCategory,
    onCategorySelected: (SearchCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchCategory.entries.forEach { c ->
            CategoryChip(
                category = c,
                selected = c == category,
                onClick = { onCategorySelected(c) },
            )
        }
    }
}

@Composable
private fun CategoryChip(
    category: SearchCategory,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val textColor = if (selected) {
        colors.onPrimary
    } else {
        colors.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .background(if (selected) colors.primary else colors.surface),
    ) {
        Text(
            text = category.label,
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Renders the catalog search results grouped by section. [state] is a
 * [CatalogQueryState.Results] whose [results] list holds one typed section per
 * category — Songs, Artists, Albums — in that order.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun renderCatalogResults(
    state: CatalogQueryState,
    onTrackSelected: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onTrackOptions: (Track) -> Unit,
    onOpenArtist: (Track) -> Unit,
    onOpenArtistCard: (CatalogArtistSummary) -> Unit,
    onOpenAlbumCard: (CatalogAlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is CatalogQueryState.Idle -> {}
        is CatalogQueryState.Loading -> LoadingIndicator()
        is CatalogQueryState.Error -> ErrorPane(message = state.message, onRetry = {})
        is CatalogQueryState.Results -> renderSections(
            state.results, onTrackSelected, onTrackOptions, onOpenArtist,
            onOpenArtistCard, onOpenAlbumCard,
        )
        else -> {}
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun renderSections(
    results: List<CatalogSearchResult>,
    onTrackSelected: (Track) -> Unit,
    onTrackOptions: (Track) -> Unit,
    onOpenArtist: (Track) -> Unit,
    onOpenArtistCard: (CatalogArtistSummary) -> Unit,
    onOpenAlbumCard: (CatalogAlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val songs = results.filterIsInstance<CatalogSearchResult.Tracks>().firstOrNull()?.tracks ?: emptyList()
        if (songs.isNotEmpty()) {
            item {
                Text(
                    text = "Songs",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(songs, key = { "song|${it.providerId}|${it.id}" }) { track ->
                SongResultRow(
                    track = track,
                    onTrackSelected = onTrackSelected,
                    onTrackOptions = onTrackOptions,
                    onOpenArtist = onOpenArtist,
                )
            }
        }
        val artists = results.filterIsInstance<CatalogSearchResult.Artists>().firstOrNull()?.items ?: emptyList()
        if (artists.isNotEmpty()) {
            item {
                Text(
                    text = "Artists",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(artists, key = { "artist|${it.providerId}|${it.id}" }) { artist ->
                        ArtistCard(
                            artist = artist,
                            onOpenArtist = onOpenArtistCard,
                        )
                    }
                }
            }
        }
        val albums = results.filterIsInstance<CatalogSearchResult.Albums>().firstOrNull()?.items ?: emptyList()
        if (albums.isNotEmpty()) {
            item {
                Text(
                    text = "Albums",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(albums, key = { "album|${it.providerId}|${it.id}" }) { album ->
                        AlbumCard(
                            album = album,
                            onOpenAlbum = onOpenAlbumCard,
                        )
                    }
                }
            }
        }
    }
}

/** Minimal provider selector: a TextButton + DropdownMenu next to the provider
 *  label. No provider-management architecture — just activating a ProviderId.
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

/**
 * M17: horizontal carousel of recent listening events, newest first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentlyPlayedRow(
    entries: List<History>,
    onEntrySelected: (Track) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Recently Played",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(entries) { _, entry ->
                Column(
                    modifier = Modifier
                        .width(CAROUSEL_ITEM_WIDTH)
                        .clickable { onEntrySelected(entry.toTrack()) },
                ) {
                    ArtworkImage(
                        artworkUrl = entry.artworkUrl,
                        trackTitle = entry.title,
                        contentDescription = entry.title,
                        cornerRadius = 8.dp,
                        glyphSize = 28.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(CAROUSEL_ITEM_WIDTH),
                    )
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        text = entry.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Fixed width of a carousel item so the row stays horizontally scrollable. */
private val CAROUSEL_ITEM_WIDTH = 120.dp

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongResultRow(
    track: Track,
    onTrackSelected: (Track) -> Unit,
    onTrackOptions: (Track) -> Unit,
    onOpenArtist: (Track) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onTrackSelected(track) },
                onLongClick = { onTrackOptions(track) },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            artworkUrl = track.artworkUrl,
            trackTitle = track.title,
            contentDescription = null,
            cornerRadius = 6.dp,
            glyphSize = 14.sp,
            modifier = Modifier
                .padding(end = 12.dp)
                .size(44.dp),
        )
        Column {
            Text(text = track.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (CatalogDetailActions.isArtistNavigable(track)) {
                    Modifier.clickable { onOpenArtist(track) }
                } else {
                    Modifier
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistCard(
    artist: CatalogArtistSummary,
    onOpenArtist: (CatalogArtistSummary) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable { onOpenArtist(artist) }
    ) {
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(
    album: CatalogAlbumSummary,
    onOpenAlbum: (CatalogAlbumSummary) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable { onOpenAlbum(album) }
    ) {
        Text(
            text = album.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

/**
 * User-facing text for a playback error.
 */
fun PlaybackError.message(): String = when (this) {
    PlaybackError.UNAVAILABLE -> "Playback isn't available for this item."
    PlaybackError.UNRESOLVABLE -> "Couldn't play this item — check your connection and try again."
    PlaybackError.QUEUE_UNPLAYABLE -> "Unable to play queued tracks."
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
                searchState = CatalogQueryState.Idle,
                recentlyPlayed = listOf(
                    History(
                        id = 1L,
                        providerId = "itunes",
                        trackId = "recent-1",
                        title = "Around the World",
                        artist = "Daft Punk",
                        album = "Discovery",
                        artworkUrl = "https://example.test/600x600bb.jpg",
                        durationMs = 429_000L,
                        playedAt = 1_727_600_000_000L,
                    ),
                ),
            ),
            playerState = PlayerState(),
            playbackError = null,
            isCurrentTrackFavorite = false,
            onQueryChange = {},
            onRetry = {},
            onTrackSelected = {},
            onHistoryEntrySelected = {},
            onTogglePlayPause = {},
            onSkipToNext = {},
            onSkipToPrevious = {},
            onToggleFavorite = {},
            onSeek = {},
            onOpenPlayer = {},
            onPlaybackErrorShown = {},
            audioPermissionGranted = true,
            onRequestAudioPermission = {},
            onSelectProvider = {},
            onSelectCategory = {},
            onOpenLibrary = {},
            onAddToPlaylist = {},
            onTrackOptions = {},
            onOpenArtist = {},
            onOpenArtistCard = {},
            onOpenAlbumCard = {},
        )
    }
}
