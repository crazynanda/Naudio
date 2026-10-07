package com.naudio.data.repository

import com.naudio.core.model.AlbumDetail
import com.naudio.core.model.ArtistDetail
import com.naudio.core.model.Track
import com.naudio.data.provider.ProviderRegistry
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.Page
import com.naudio.provider.api.PageToken
import com.naudio.provider.api.ProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M21 — provider-agnostic catalog lookup in [LibraryRepository].
 *
 * The contract under test is ROUTING: an artist or album is resolved through the
 * metadata provider registered under the caller's `providerId`, never through
 * whichever provider happens to be active. That mirrors the rule
 * [LibraryRepositoryTest] already pins down for playback, and it is the rule
 * that keeps a catalog id from being interpreted by the wrong catalog.
 *
 * Providers that do not implement catalog detail must degrade to null rather
 * than failing, because "this provider has no artist catalog" is a normal
 * capability answer, not an error.
 */
class LibraryCatalogLookupTest {

    /** A provider that implements catalog detail and records what it was asked. */
    private class CatalogProvider(
        override val id: ProviderId,
        private val artist: ArtistDetail? = null,
        private val album: AlbumDetail? = null,
    ) : MetadataProvider {
        override val displayName: String = id.value
        val artistRequests = mutableListOf<String>()
        val albumRequests = mutableListOf<String>()
        var artistError: Exception? = null
        var albumError: Exception? = null

        override suspend fun searchTracks(
            query: String,
            token: PageToken?,
            limit: Int,
        ): Page<Track> = Page(emptyList(), nextToken = null)

        override suspend fun lookupTrack(id: String): Track? = null

        override suspend fun getArtist(artistId: String): ArtistDetail? {
            artistRequests += artistId
            artistError?.let { throw it }
            return artist
        }

        override suspend fun getAlbum(albumId: String): AlbumDetail? {
            albumRequests += albumId
            albumError?.let { throw it }
            return album
        }
    }

    /** A provider that inherits the interface DEFAULTS (no catalog detail). */
    private class BasicProvider(override val id: ProviderId) : MetadataProvider {
        override val displayName: String = id.value
        override suspend fun searchTracks(
            query: String,
            token: PageToken?,
            limit: Int,
        ): Page<Track> = Page(emptyList(), nextToken = null)

        override suspend fun lookupTrack(id: String): Track? = null
    }

    private val artistDetail = ArtistDetail(
        id = "UC1",
        providerId = "ytmusic",
        name = "An Artist",
        tracks = listOf(Track("v1", "ytmusic", "T1", "A")),
        albums = listOf(com.naudio.core.model.AlbumSummary("MPREb_1", "ytmusic", "Album")),
    )

    private val albumDetail = AlbumDetail(
        id = "MPREb_1",
        providerId = "ytmusic",
        title = "Album",
        artist = "An Artist",
        year = "1989",
        tracks = listOf(
            Track("zzz", "ytmusic", "First", "A"),
            Track("aaa", "ytmusic", "Second", "A"),
        ),
    )

    private fun repository(
        providers: List<MetadataProvider>,
        activeId: String = "itunes",
    ): LibraryRepository = LibraryRepository(
        ProviderRegistry(providers).apply { activate(ProviderId(activeId)) },
    )

    // ------------------------------------------------------------------
    // Routing by provider id, not by the active provider
    // ------------------------------------------------------------------

    @Test
    fun `artist lookup routes to the provider named by providerId`() = runTest {
        val yt = CatalogProvider(ProviderId("ytmusic"), artist = artistDetail)
        val repository = repository(
            listOf(BasicProvider(ProviderId("itunes")), yt),
            activeId = "itunes",
        )

        val result = repository.artist("ytmusic", "UC1")

        assertEquals("UC1", result?.id)
        assertEquals(listOf("UC1"), yt.artistRequests)
    }

    @Test
    fun `album lookup routes to the provider named by providerId even when another is active`() = runTest {
        val yt = CatalogProvider(ProviderId("ytmusic"), album = albumDetail)
        val itunes = BasicProvider(ProviderId("itunes"))
        val repository = repository(listOf(itunes, yt), activeId = "itunes")

        val result = repository.album("ytmusic", "MPREb_1")

        assertEquals("MPREb_1", result?.id)
        assertEquals("1989", result?.year)
        assertEquals(listOf("MPREb_1"), yt.albumRequests)
    }

    @Test
    fun `an album's track order is returned exactly as the provider gave it`() = runTest {
        val yt = CatalogProvider(ProviderId("ytmusic"), album = albumDetail)
        val repository = repository(listOf(yt))

        val tracks = repository.album("ytmusic", "MPREb_1")?.tracks

        // Release order, not sorted: the repository must not reorder it either.
        assertEquals(listOf("zzz", "aaa"), tracks?.map { it.id })
    }

    // ------------------------------------------------------------------
    // Providers without the capability, and unknown providers
    // ------------------------------------------------------------------

    @Test
    fun `a provider that does not implement catalog detail returns null`() = runTest {
        val repository = repository(listOf(BasicProvider(ProviderId("itunes"))))

        assertNull(repository.artist("itunes", "anything"))
        assertNull(repository.album("itunes", "anything"))
    }

    @Test
    fun `an unregistered provider returns null instead of failing`() = runTest {
        val repository = repository(listOf(BasicProvider(ProviderId("itunes"))))

        assertNull(repository.artist("nosuchprovider", "UC1"))
        assertNull(repository.album("nosuchprovider", "MPREb_1"))
    }

    @Test
    fun `a registered provider that has no such entity returns null`() = runTest {
        val yt = CatalogProvider(ProviderId("ytmusic"), artist = null, album = null)
        val repository = repository(listOf(yt))

        assertNull(repository.artist("ytmusic", "UCmissing"))
        assertNull(repository.album("ytmusic", "MPREbmissing"))
    }

    // ------------------------------------------------------------------
    // Failures propagate — the repository never converts them into null
    // ------------------------------------------------------------------

    @Test
    fun `a lookup failure propagates rather than degrading to null`() = runTest {
        val yt = CatalogProvider(ProviderId("ytmusic"), artist = artistDetail).apply {
            artistError = IllegalStateException("network down")
        }
        val repository = repository(listOf(yt))

        val thrown = runCatching { repository.artist("ytmusic", "UC1") }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `cancellation is never swallowed into an empty result`() = runTest {
        val yt = CatalogProvider(ProviderId("ytmusic"), artist = artistDetail).apply {
            artistError = CancellationException("cancelled")
        }
        val repository = repository(listOf(yt))

        val thrown = runCatching { repository.artist("ytmusic", "UC1") }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    // ------------------------------------------------------------------
    // Registry lookup used by the repository
    // ------------------------------------------------------------------

    @Test
    fun `metadataProvider resolves an exact registered id`() {
        val catalog = CatalogProvider(ProviderId("ytmusic"), artist = artistDetail)
        val registry = ProviderRegistry(listOf(BasicProvider(ProviderId("itunes")), catalog))

        assertEquals(ProviderId("ytmusic"), registry.metadataProvider(ProviderId("ytmusic"))?.id)
        assertNull(registry.metadataProvider(ProviderId("nope")))
    }
}