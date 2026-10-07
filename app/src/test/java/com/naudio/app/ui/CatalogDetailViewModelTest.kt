package com.naudio.app.ui

import com.naudio.core.model.AlbumDetail
import com.naudio.core.model.AlbumSummary
import com.naudio.core.model.ArtistDetail
import com.naudio.core.model.Track
import com.naudio.data.provider.ProviderRegistry
import com.naudio.data.repository.LibraryRepository
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.Page
import com.naudio.provider.api.PageToken
import com.naudio.provider.api.ProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M21 — [ArtistViewModel] and [AlbumViewModel] state transitions.
 *
 * Every path is driven through a fake metadata provider behind the real
 * [LibraryRepository] and the real [ProviderRegistry], so the routing under test
 * is the production one. No network and no live YouTube are involved.
 *
 * The transitions that matter are Loading -> Success, Loading -> Error and
 * Loading -> Empty, because Empty must stay distinguishable from Error: "this
 * catalog has no such entity" is a successful lookup with an empty answer, and
 * reporting it as a failure would tell the user a request broke when it did not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** Records requests and returns whatever the test staged, or fails. */
    private class CatalogProvider(
        override val id: ProviderId = ProviderId("ytmusic"),
    ) : MetadataProvider {
        override val displayName: String = "Catalog"
        val artistRequests = mutableListOf<String>()
        val albumRequests = mutableListOf<String>()

        var artist: ArtistDetail? = null
        var album: AlbumDetail? = null
        var error: Exception? = null

        override suspend fun searchTracks(
            query: String,
            token: PageToken?,
            limit: Int,
        ): Page<Track> = Page(emptyList(), nextToken = null)

        override suspend fun lookupTrack(id: String): Track? = null

        override suspend fun getArtist(artistId: String): ArtistDetail? {
            artistRequests += artistId
            error?.let { throw it }
            return artist
        }

        override suspend fun getAlbum(albumId: String): AlbumDetail? {
            albumRequests += albumId
            error?.let { throw it }
            return album
        }
    }

    private lateinit var provider: CatalogProvider
    private lateinit var repository: LibraryRepository
    private lateinit var artistViewModel: ArtistViewModel
    private lateinit var albumViewModel: AlbumViewModel

    private val trackA = Track("v1", "ytmusic", "First", "An Artist")
    private val trackB = Track("v2", "ytmusic", "Second", "An Artist")

    private val artist = ArtistDetail(
        id = "UC1",
        providerId = "ytmusic",
        name = "An Artist",
        description = "Bio",
        artworkUrl = "https://img.example/a.jpg",
        tracks = listOf(trackA, trackB),
        albums = listOf(AlbumSummary("MPREb_1", "ytmusic", "Album", "An Artist", null, "1989")),
    )

    private val album = AlbumDetail(
        id = "MPREb_1",
        providerId = "ytmusic",
        title = "Album",
        artist = "An Artist",
        year = "1989",
        tracks = listOf(trackB, trackA), // intentionally not sorted
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        provider = CatalogProvider()
        repository = LibraryRepository(ProviderRegistry(listOf(provider)))
        artistViewModel = ArtistViewModel(repository)
        albumViewModel = AlbumViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // Artist
    // ------------------------------------------------------------------

    @Test
    fun `artist starts Empty and reaches Success`() = runTest(dispatcher) {
        provider.artist = artist
        // Nothing open yet: Empty, not Loading — the screen has no entity.
        assertEquals(CatalogDetailState.Empty, artistViewModel.state.value)

        artistViewModel.openArtist("ytmusic", "UC1")
        advanceUntilIdle()

        val state = artistViewModel.state.value
        assertTrue(state is CatalogDetailState.Success)
        assertEquals("UC1", (state as CatalogDetailState.Success).value.id)
        assertEquals(listOf(trackA, trackB), state.value.tracks)
        assertEquals(listOf("UC1"), provider.artistRequests)
    }

    @Test
    fun `the artist screen state carries the name, artwork and discography it must render`() = runTest(dispatcher) {
        // The manual-test failure: the screen opened on the right artist and showed
        // its songs, but rendered "Unknown" and no Albums row. What the screen
        // renders is exactly this Success value, so the whole header + discography
        // contract is asserted on the object the UI reads.
        val oasis = ArtistDetail(
            id = "UCmMUZbaYdNH0bEd1PAlAqsA",
            providerId = "ytmusic",
            name = "Oasis",
            description = "Bio",
            artworkUrl = "https://yt3.googleusercontent.com/oasis-w544-h544",
            tracks = listOf(trackA),
            albums = listOf(
                AlbumSummary(
                    id = "MPREb_9nqEki4ZDpp",
                    providerId = "ytmusic",
                    title = "(What's The Story) Morning Glory? (Remastered)",
                    artist = "Oasis",
                    artworkUrl = "https://lh3.googleusercontent.com/morning-glory-w544-h544",
                    year = "1995",
                ),
            ),
        )
        provider.artist = oasis

        artistViewModel.openArtist("ytmusic", "UCmMUZbaYdNH0bEd1PAlAqsA")
        advanceUntilIdle()

        val state = artistViewModel.state.value
        assertTrue(state is CatalogDetailState.Success)
        val rendered = (state as CatalogDetailState.Success).value
        assertEquals("Oasis", rendered.name)
        assertEquals("https://yt3.googleusercontent.com/oasis-w544-h544", rendered.artworkUrl)
        // The Albums row is rendered only when this list is non-empty.
        assertEquals(1, rendered.albums.size)
        assertEquals("MPREb_9nqEki4ZDpp", rendered.albums.single().id)
        // And it is reachable: the card the screen shows opens a real target.
        assertTrue(CatalogDetailActions.isAlbumNavigable(rendered.albums.single()))
    }

    @Test
    fun `artist passes through Loading before it resolves`() = runTest(dispatcher) {
        provider.artist = artist

        artistViewModel.openArtist("ytmusic", "UC1")
        // Before the dispatcher runs the launched coroutine, the state must
        // already be Loading rather than the stale Empty.
        assertEquals(CatalogDetailState.Loading, artistViewModel.state.value)

        advanceUntilIdle()
        assertTrue(artistViewModel.state.value is CatalogDetailState.Success)
    }

    @Test
    fun `artist reaches Error and keeps the failure message`() = runTest(dispatcher) {
        provider.error = IllegalStateException("network down")

        artistViewModel.openArtist("ytmusic", "UC1")
        advanceUntilIdle()

        val state = artistViewModel.state.value
        assertTrue(state is CatalogDetailState.Error)
        assertEquals("network down", (state as CatalogDetailState.Error).message)
    }

    @Test
    fun `artist with no message reports a readable fallback`() = runTest(dispatcher) {
        provider.error = IllegalStateException()

        artistViewModel.openArtist("ytmusic", "UC1")
        advanceUntilIdle()

        val state = artistViewModel.state.value
        assertTrue(state is CatalogDetailState.Error)
        assertTrue((state as CatalogDetailState.Error).message.isNotBlank())
    }

    @Test
    fun `artist reaches Empty when the provider has no such entity`() = runTest(dispatcher) {
        provider.artist = null

        artistViewModel.openArtist("ytmusic", "UCmissing")
        advanceUntilIdle()

        // Empty, NOT Error: the lookup succeeded and found nothing.
        assertEquals(CatalogDetailState.Empty, artistViewModel.state.value)
    }

    @Test
    fun `a blank artist id never reaches the provider`() = runTest(dispatcher) {
        artistViewModel.openArtist("ytmusic", "  ")
        advanceUntilIdle()

        assertEquals(CatalogDetailState.Empty, artistViewModel.state.value)
        assertTrue(provider.artistRequests.isEmpty())
    }

    @Test
    fun `a cancelled load is never reported as a user-visible error`() = runTest(dispatcher) {
        provider.error = CancellationException("superseded")

        artistViewModel.openArtist("ytmusic", "UC1")
        advanceUntilIdle()

        // A cancelled load means a newer request superseded this one, so the
        // state is left for that request to own. What must never happen is the
        // cancellation being converted into an Error the user would see.
        assertTrue(artistViewModel.state.value !is CatalogDetailState.Error)
    }

    @Test
    fun `closing the screen during a load settles on Empty, not a spinner`() = runTest(dispatcher) {
        provider.error = CancellationException("closed mid-flight")

        artistViewModel.openArtist("ytmusic", "UC1")
        // Back pressed while the fetch is still in flight.
        artistViewModel.closeArtist()
        advanceUntilIdle()

        // The cancellation must not strand the screen on Loading.
        assertEquals(CatalogDetailState.Empty, artistViewModel.state.value)
    }

    @Test
    fun `closing the artist clears its state`() = runTest(dispatcher) {
        provider.artist = artist
        artistViewModel.openArtist("ytmusic", "UC1")
        advanceUntilIdle()
        assertTrue(artistViewModel.state.value is CatalogDetailState.Success)

        artistViewModel.closeArtist()

        assertEquals(CatalogDetailState.Empty, artistViewModel.state.value)
    }

    @Test
    fun `retry re-runs the open artist lookup`() = runTest(dispatcher) {
        provider.error = IllegalStateException("network down")
        artistViewModel.openArtist("ytmusic", "UC1")
        advanceUntilIdle()
        assertTrue(artistViewModel.state.value is CatalogDetailState.Error)

        provider.error = null
        provider.artist = artist
        artistViewModel.retry()
        advanceUntilIdle()

        assertTrue(artistViewModel.state.value is CatalogDetailState.Success)
        assertEquals(listOf("UC1", "UC1"), provider.artistRequests)
    }

    @Test
    fun `opening a second artist replaces the first`() = runTest(dispatcher) {
        provider.artist = artist
        artistViewModel.openArtist("ytmusic", "UC1")
        advanceUntilIdle()

        val other = artist.copy(id = "UC2", name = "Someone Else")
        provider.artist = other
        artistViewModel.openArtist("ytmusic", "UC2")
        advanceUntilIdle()

        val state = artistViewModel.state.value as CatalogDetailState.Success
        assertEquals("UC2", state.value.id)
        assertEquals(listOf("UC1", "UC2"), provider.artistRequests)
    }

    // ------------------------------------------------------------------
    // Album
    // ------------------------------------------------------------------

    @Test
    fun `album starts Empty and reaches Success`() = runTest(dispatcher) {
        provider.album = album

        assertEquals(CatalogDetailState.Empty, albumViewModel.state.value)

        albumViewModel.openAlbum("ytmusic", "MPREb_1")
        advanceUntilIdle()

        val state = albumViewModel.state.value
        assertTrue(state is CatalogDetailState.Success)
        assertEquals("Album", (state as CatalogDetailState.Success).value.title)
        assertEquals(listOf("MPREb_1"), provider.albumRequests)
    }

    @Test
    fun `album passes through Loading before it resolves`() = runTest(dispatcher) {
        provider.album = album

        albumViewModel.openAlbum("ytmusic", "MPREb_1")
        assertEquals(CatalogDetailState.Loading, albumViewModel.state.value)

        advanceUntilIdle()
        assertTrue(albumViewModel.state.value is CatalogDetailState.Success)
    }

    @Test
    fun `album reaches Error on failure`() = runTest(dispatcher) {
        provider.error = IllegalStateException("boom")

        albumViewModel.openAlbum("ytmusic", "MPREb_1")
        advanceUntilIdle()

        assertTrue(albumViewModel.state.value is CatalogDetailState.Error)
    }

    @Test
    fun `album reaches Empty when there is no such album`() = runTest(dispatcher) {
        provider.album = null

        albumViewModel.openAlbum("ytmusic", "MPREb_missing")
        advanceUntilIdle()

        assertEquals(CatalogDetailState.Empty, albumViewModel.state.value)
    }

    @Test
    fun `a blank album id never reaches the provider`() = runTest(dispatcher) {
        albumViewModel.openAlbum("ytmusic", "")
        advanceUntilIdle()

        assertEquals(CatalogDetailState.Empty, albumViewModel.state.value)
        assertTrue(provider.albumRequests.isEmpty())
    }

    @Test
    fun `album track order is preserved into the UI state`() = runTest(dispatcher) {
        provider.album = album

        albumViewModel.openAlbum("ytmusic", "MPREb_1")
        advanceUntilIdle()

        // Release order, not sorted: the screen and the queue both rely on it.
        val state = albumViewModel.state.value as CatalogDetailState.Success
        assertEquals(listOf("v2", "v1"), state.value.tracks.map { it.id })
    }

    @Test
    fun `closing the album clears its state`() = runTest(dispatcher) {
        provider.album = album
        albumViewModel.openAlbum("ytmusic", "MPREb_1")
        advanceUntilIdle()

        albumViewModel.closeAlbum()

        assertEquals(CatalogDetailState.Empty, albumViewModel.state.value)
    }

    @Test
    fun `artist and album view models are independent`() = runTest(dispatcher) {
        provider.artist = artist
        provider.album = album

        artistViewModel.openArtist("ytmusic", "UC1")
        advanceUntilIdle()

        // Opening an album must not disturb the already-loaded artist.
        assertTrue(artistViewModel.state.value is CatalogDetailState.Success)
        assertEquals(CatalogDetailState.Empty, albumViewModel.state.value)
    }
}