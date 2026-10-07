package com.naudio.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naudio.app.ui.theme.NaudioTheme
import com.naudio.core.model.AlbumDetail
import com.naudio.core.model.AlbumSummary
import com.naudio.core.model.ArtistDetail
import com.naudio.core.model.Track

/**
 * Artist detail screen (M21).
 *
 * Pure UDF rendering, exactly like [PlaylistDetailScreen]: it receives a
 * [CatalogDetailState] and emits intents, and holds no fetch or playback logic.
 * The one structural difference from the existing screens is that the header and
 * the release row scroll with the content, so the page is a SINGLE
 * [LazyColumn] with a [LazyRow] nested inside it for the discography. Nesting a
 * second vertical lazy container would make the release row scroll vertically
 * inside the page and break its bounded height, so the horizontal list is the
 * only nested lazy scope here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    state: CatalogDetailState<ArtistDetail>,
    onBack: () -> Unit,
    onPlayTopTracks: (List<Track>) -> Unit,
    onTrackSelected: (List<Track>, Int) -> Unit,
    onAlbumSelected: (AlbumSummary, ArtistDetail) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                // The artist name is already shown in the header below, so the
                // bar title stays generic — and it has to render something before
                // Loading resolves.
                title = {
                    Text(
                        text = (state as? CatalogDetailState.Success)?.value?.name ?: "Artist",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { innerPadding ->
        CatalogDetailBody(
            state = state,
            loadingText = "Loading artist…",
            emptyText = "This artist isn't available.",
            onRetry = null,
            modifier = Modifier.padding(innerPadding),
            content = { artist ->
                Column {
                    ArtistHeader(artist)
                    // The discography is a bounded horizontal row, so it lives
                    // here in the column rather than as another lazy section.
                    if (artist.albums.isNotEmpty()) {
                        AlbumRow(
                            albums = artist.albums,
                            artist = artist,
                            onAlbumSelected = { album, artist -> onAlbumSelected(album, artist) },
                        )
                    }
                    TrackSection(
                        title = "Songs",
                        tracks = artist.tracks,
                        primaryActionLabel = "Play top songs",
                        onPrimaryAction = { onPlayTopTracks(artist.tracks) },
                        onTrackSelected = { index -> onTrackSelected(artist.tracks, index) },
                    )
                }
            },
        )
    }
}

/**
 * Album detail screen (M21).
 *
 * The track list is rendered in the exact order [AlbumDetail.tracks] supplies —
 * release order — because both the list and the queue built from it depend on it.
 * Nothing on this screen sorts or reorders tracks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    state: CatalogDetailState<AlbumDetail>,
    onBack: () -> Unit,
    onPlayAlbum: (List<Track>) -> Unit,
    onTrackSelected: (List<Track>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = (state as? CatalogDetailState.Success)?.value?.title ?: "Album",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { innerPadding ->
        CatalogDetailBody(
            state = state,
            loadingText = "Loading album…",
            emptyText = "This album isn't available.",
            onRetry = null,
            modifier = Modifier.padding(innerPadding),
            content = { album ->
                Column {
                    AlbumHeader(album)
                    TrackSection(
                        title = "${album.tracks.size} tracks",
                        tracks = album.tracks,
                        primaryActionLabel = "Play album",
                        onPrimaryAction = { onPlayAlbum(album.tracks) },
                        onTrackSelected = { index -> onTrackSelected(album.tracks, index) },
                        trackArtwork = { it.artworkUrl?.takeIf { it.isNotBlank() } ?: album.artworkUrl ?: "" },
                    )
                }
            },
        )
    }
}

// ----------------------------------------------------------------------
// Shared scaffolding
// ----------------------------------------------------------------------

/**
 * The Loading / Empty / Error / Success shell both screens share.
 *
 * Rendering is driven entirely by the state, so a screen can never show an empty
 * header over a spinner or an error over stale content. [content] is only
 * invoked for a loaded entity.
 */
@Composable
private fun <T> CatalogDetailBody(
    state: CatalogDetailState<T>,
    loadingText: String,
    emptyText: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is CatalogDetailState.Loading -> CenteredMessage(loadingText, showSpinner = true)
        is CatalogDetailState.Empty -> CenteredMessage(emptyText)
        is CatalogDetailState.Error -> CenteredMessage(
            message = state.message,
            actionLabel = onRetry?.let { "Retry" },
            onAction = onRetry,
        )
        is CatalogDetailState.Success -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { content(state.value) }
        }
    }
}

@Composable
private fun CenteredMessage(
    message: String,
    showSpinner: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showSpinner) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ArtistHeader(artist: ArtistDetail) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtworkImage(
            artworkUrl = artist.artworkUrl,
            trackTitle = artist.name,
            contentDescription = artist.name,
            cornerRadius = 12.dp,
            modifier = Modifier.size(180.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 3,
            // Long artist names shrink into an ellipsis rather than pushing the
            // rest of the header off screen on a small display.
            overflow = TextOverflow.Ellipsis,
        )
        // The description is optional; an absent bio simply renders nothing.
        artist.description?.let { description ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AlbumHeader(album: AlbumDetail) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            artworkUrl = album.artworkUrl,
            trackTitle = album.title,
            contentDescription = album.title,
            cornerRadius = 8.dp,
            modifier = Modifier.size(140.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            // Artist and year are both optional: neither is rendered when the
            // provider did not report it.
            album.artist?.let { artist ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            album.year?.let { year ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = year,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The horizontal discography row.
 *
 * Each card carries the album's own [AlbumSummary.id], so selecting one opens
 * exactly the album that was tapped — no id is derived from the title, which
 * would be a guess.
 */
@Composable
private fun AlbumRow(albums: List<AlbumSummary>, artist: ArtistDetail, onAlbumSelected: (AlbumSummary, ArtistDetail) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Albums",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums, key = { it.id }) { album ->
                Column(
                    modifier = Modifier
                        .width(140.dp)
                        .clickable { onAlbumSelected(album, artist) },
                ) {
                    ArtworkImage(
                        artworkUrl = album.artworkUrl,
                        trackTitle = album.title,
                        contentDescription = album.title,
                        cornerRadius = 8.dp,
                        modifier = Modifier.size(140.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = album.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    album.year?.let { year ->
                        Text(
                            text = year,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The shared "section title + primary action + ordered track list" block.
 *
 * The primary action is disabled when there is nothing to play, so an empty
 * artist's Songs section cannot push an empty queue into the coordinator.
 */
@Composable
private fun TrackSection(
    title: String,
    tracks: List<Track>,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    onTrackSelected: (Int) -> Unit,
    /** Artwork to paint for each track. Defaults to the track's own URL;
     *  a caller that owns an album may supply a fallback (typically the album&rsquo;s
     *  artwork) so that a track without individual artwork still renders. */
    trackArtwork: ((Track) -> String)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onPrimaryAction, enabled = tracks.isNotEmpty()) {
                Text(primaryActionLabel)
            }
        }
        if (tracks.isEmpty()) {
            Text(
                text = "No tracks available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            return
        }
        tracks.forEachIndexed { index, track ->
            CatalogTrackRow(
                index = index,
                track = track,
                onClick = { onTrackSelected(index) },
                trackArtwork = trackArtwork,
            )
        }
    }
}

/**
 * One track row: position, artwork, title and artist — the same visual language
 * the playlist and queue rows use, so a track looks identical wherever it is
 * listed. Rows are emitted by [TrackSection] rather than by a nested lazy list,
 * because they already live inside the page's single [LazyColumn].
 */
@Composable
private fun CatalogTrackRow(
    index: Int,
    track: Track,
    onClick: () -> Unit,
    /** Artwork to paint for this row. Defaults to the track's own URL, but the
     *  caller (an album list) may supply a fallback, normally the album&rsquo;s
     *  artwork, so a track without individual artwork still renders. */
    trackArtwork: ((Track) -> String)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(end = 12.dp)
                .size(width = 20.dp, height = 44.dp),
        )
        ArtworkImage(
            artworkUrl = trackArtwork?.invoke(track) ?: track.artworkUrl,
            trackTitle = track.title,
            contentDescription = null,
            cornerRadius = 6.dp,
            glyphSize = 14.sp,
            modifier = Modifier
                .padding(end = 12.dp)
                .size(44.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
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
    }
}

// ----------------------------------------------------------------------
// Previews
// ----------------------------------------------------------------------

@Preview(showBackground = true)
@Composable
private fun ArtistDetailScreenPreview() {
    NaudioTheme {
        ArtistDetailScreen(
            state = CatalogDetailState.Success(
                ArtistDetail(
                    id = "UC1",
                    providerId = "ytmusic",
                    name = "An Artist With A Very Long Name That Needs To Ellipsize Gracefully",
                    description = "4.4M subscribers",
                    tracks = listOf(
                        Track("v1", "ytmusic", "First Song", "An Artist"),
                        Track("v2", "ytmusic", "Second Song", "An Artist"),
                    ),
                    albums = listOf(
                        AlbumSummary("MPREb_1", "ytmusic", "First Album", "An Artist", null, "1989"),
                        AlbumSummary("MPREb_2", "ytmusic", "Second Album", "An Artist"),
                    ),
                ),
            ),
            onBack = {},
            onPlayTopTracks = {},
            onTrackSelected = { _, _ -> },
            onAlbumSelected = { _, _ -> },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AlbumDetailScreenPreview() {
    NaudioTheme {
        AlbumDetailScreen(
            state = CatalogDetailState.Success(
                AlbumDetail(
                    id = "MPREb_1",
                    providerId = "ytmusic",
                    title = "Whenever You Need Somebody",
                    artist = "Rick Astley",
                    year = "1989",
                    tracks = listOf(
                        Track("v1", "ytmusic", "Track One", "Rick Astley"),
                        Track("v2", "ytmusic", "Track Two", "Rick Astley"),
                    ),
                ),
            ),
            onBack = {},
            onPlayAlbum = {},
            onTrackSelected = { _, _ -> },
        )
    }
}
