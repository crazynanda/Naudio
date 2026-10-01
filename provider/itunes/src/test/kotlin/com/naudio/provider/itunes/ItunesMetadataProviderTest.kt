package com.naudio.provider.itunes

import com.naudio.core.network.NetworkException
import com.naudio.provider.api.PageToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.net.ConnectException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic [MockEngine]-based tests: no real network access.
 * The provider under test is the production-configuration client from
 * [NaudioHttpClient.create] on a MockEngine (via the internal engine seam).
 */
class ItunesMetadataProviderTest {

    /**
     * Minimal production-faithful client: expectSuccess drives the HTTP error
     * mapping; JSON decoding and bounded retry come from the real network
     * layer ([NaudioHttpClient.json] / [NaudioHttpClient.requestWithRetry])
     * through the provider under test.
     */
    private fun clientWith(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) { expectSuccess = true }

    private fun provider(handler: MockRequestHandler): ItunesMetadataProvider =
        ItunesMetadataProvider(clientWith(handler))

    // ------------------------------------------------------------------
    // 1. search returns mapped tracks
    // ------------------------------------------------------------------
    @Test
    fun `search returns mapped tracks`() = runTest {
        val provider = provider(respondJson(searchJson(2)))
        val page = provider.searchTracks("daft punk")
        assertEquals(2, page.items.size)
        assertEquals("1", page.items[0].id)
        assertEquals("itunes", page.items[0].providerId)
        assertEquals("Track 1", page.items[0].title)
        assertEquals("Daft Punk", page.items[0].artist)
        assertEquals("Discovery", page.items[0].album)
        assertEquals(100L, page.items[0].durationMs)
    }

    // ------------------------------------------------------------------
    // 2. full page -> nextToken = Offset(offset + items.size)
    // ------------------------------------------------------------------
    @Test
    fun `search calculates nextToken when page is full`() = runTest {
        val provider = provider(respondJson(searchJson(3)))
        val page = provider.searchTracks("daft punk", token = PageToken.Offset(20), limit = 3)
        assertEquals(3, page.items.size)
        assertEquals(PageToken.Offset(23), page.nextToken)
    }

    // ------------------------------------------------------------------
    // 3. short page -> nextToken = null
    // iTunes returns all matching results and resultCount equals results.size.
    // ------------------------------------------------------------------
    @Test
    fun `search returns null nextToken when fewer results are returned`() = runTest {
        val provider = provider(respondJson(searchJson(2)))
        val page = provider.searchTracks("daft punk", limit = 3)
        assertEquals(2, page.items.size)
        assertNull(page.nextToken)
    }

    // ------------------------------------------------------------------
    // 4. empty result set -> empty page, null nextToken
    // ------------------------------------------------------------------
    @Test
    fun `search returns empty page for zero results`() = runTest {
        val provider = provider(respondJson("""{"resultCount":0,"results":[]}"""))
        val page = provider.searchTracks("zzzzz")
        assertTrue(page.items.isEmpty())
        assertNull(page.nextToken)
    }

    // ------------------------------------------------------------------
    // 4b. incompatible token type fails deterministically (never reinterpreted)
    // ------------------------------------------------------------------
    @Test
    fun `search rejects an opaque token instead of reinterpreting it`() = runTest {
        val provider = provider(respondJson(searchJson(1)))
        val ex = assertFailsWith<IllegalArgumentException> {
            provider.searchTracks("daft punk", token = PageToken.Opaque("continuation-token"))
        }
        assertEquals(ItunesMetadataProvider.TOKEN_TYPE_ERROR, ex.message)
    }

    // ------------------------------------------------------------------
    // 5. lookup returns mapped track
    // ------------------------------------------------------------------
    @Test
    fun `lookup returns mapped track`() = runTest {
        val provider = provider(respondJson(lookupJson()))
        val track = provider.lookupTrack("1440913503")
        assertNotNull(track)
        assertEquals("1440913503", track.id)
        assertEquals("itunes", track.providerId)
        assertEquals("Harder, Better, Faster, Stronger", track.title)
        assertEquals("Daft Punk", track.artist)
        assertEquals("Discovery", track.album)
        assertEquals("https://is1-ssl.mzstatic.com/100x100.jpg", track.artworkUrl)
        assertEquals(224_000L, track.durationMs)
    }

    // ------------------------------------------------------------------
    // 6. zero-result lookup -> null (iTunes reports miss as 200 + resultCount 0)
    // ------------------------------------------------------------------
    @Test
    fun `lookup returns null for zero-result lookup`() = runTest {
        val provider = provider(respondJson("""{"resultCount":0,"results":[]}"""))
        assertNull(provider.lookupTrack("999999999"))
    }

    // ------------------------------------------------------------------
    // 7. transport failure propagates as NetworkException (not empty result)
    // ------------------------------------------------------------------
    @Test
    fun `network failure propagates as NetworkException`() = runTest {
        val provider = provider(failingHandler(ConnectException("Connection refused")))
        val ex = assertFailsWith<NetworkException.Connectivity> {
            provider.searchTracks("daft punk")
        }
        assertEquals("Connection refused", ex.message)
    }

    @Test
    fun `http 5xx propagates as NetworkException HttpError`() = runTest {
        val provider = provider(
            respondJson("""{"resultCount":0,"results":[]}""", HttpStatusCode.BadGateway),
        )
        assertFailsWith<NetworkException.HttpError> {
            provider.searchTracks("daft punk")
        }
    }

    // ------------------------------------------------------------------
    // 8. request URL / query parameters are correct
    // ------------------------------------------------------------------
    @Test
    fun `search request carries term entity limit offset parameters`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val provider = provider { request ->
            requests.add(request)
            respond(searchJson(1), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        provider.searchTracks("daft punk", token = PageToken.Offset(25), limit = 10)
        val url = requests.single().url
        assertEquals("https", url.protocol.name)
        assertEquals("itunes.apple.com", url.host)
        assertEquals("/search", url.encodedPath)
        assertEquals("daft punk", url.parameters["term"])
        assertEquals("song", url.parameters["entity"])
        assertEquals("10", url.parameters["limit"])
        assertEquals("25", url.parameters["offset"])
    }

    @Test
    fun `lookup request carries id parameter`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val provider = provider { request ->
            requests.add(request)
            respond(lookupJson(), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        provider.lookupTrack("1440913503")
        val url = requests.single().url
        assertEquals("https://itunes.apple.com/lookup", url.toString().substringBefore("?"))
        assertEquals("1440913503", url.parameters["id"])
    }

    // ------------------------------------------------------------------
    // 9. mapping preserves album/artwork/duration
    // ------------------------------------------------------------------
    @Test
    fun `dto mapping preserves album artwork and duration`() = runTest {
        val dto = ItunesTrackDto(
            trackId = 42L,
            trackName = "Song",
            artistName = "Artist",
            collectionName = "Album",
            artworkUrl100 = "https://example.com/art.jpg",
            trackTimeMillis = 185_000L,
        )
        val track = dto.toDomain()
        assertEquals("42", track.id)
        assertEquals("itunes", track.providerId)
        assertEquals("Song", track.title)
        assertEquals("Artist", track.artist)
        assertEquals("Album", track.album)
        assertEquals("https://example.com/art.jpg", track.artworkUrl)
        assertEquals(185_000L, track.durationMs)
    }

    // ------------------------------------------------------------------
    // 9b. M12: artwork URL upscale (safe fallback in every edge case)
    // ------------------------------------------------------------------

    @Test
    fun `standard 100x100bb artwork is upgraded to 600x600bb`() {
        val dto = ItunesTrackDto(
            trackId = 1L,
            trackName = "Song",
            artistName = "Artist",
            artworkUrl100 = "https://is1-ssl.mzstatic.com/image/thumb/Music/1.jpg/100x100bb.jpg",
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/1.jpg/600x600bb.jpg",
            dto.toDomain().artworkUrl,
        )
    }

    @Test
    fun `null artwork stays null through the mapper`() {
        val dto = ItunesTrackDto(trackId = 1L, trackName = "Song", artistName = "Artist", artworkUrl100 = null)
        assertNull(dto.toDomain().artworkUrl)
    }

    @Test
    fun `unexpected artwork url format is preserved verbatim`() {
        val dto = ItunesTrackDto(
            trackId = 1L,
            trackName = "Song",
            artistName = "Artist",
            artworkUrl100 = "https://example.com/some/odd/art.png",
        )
        assertEquals("https://example.com/some/odd/art.png", dto.toDomain().artworkUrl)
    }

    @Test
    fun `non-100 sizes in the bb pattern are left untouched`() {
        // Only the known 100x100bb token is rewritten; other sizes pass through.
        assertEquals("https://x/200x200bb.jpg", "https://x/200x200bb.jpg".upgradedArtworkUrl())
    }

    // ------------------------------------------------------------------
    // 10. cancellation is not swallowed by the retry wrapper
    // ------------------------------------------------------------------
    @Test
    fun `cancellation is not swallowed`() = runTest {
        val provider = provider(failingHandler(CancellationException("caller cancelled")))
        assertFailsWith<CancellationException> {
            provider.searchTracks("daft punk")
        }
    }

    @Test
    fun `blank search returns empty page without a network call`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val provider = provider { request ->
            requests.add(request)
            respond(searchJson(1), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val page = provider.searchTracks("   ")
        assertTrue(page.items.isEmpty())
        assertNull(page.nextToken)
        assertTrue(requests.isEmpty())
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): MockRequestHandler = { respond(body, status, headersOf("Content-Type", "application/json")) }

    /** Deterministic iTunes-shaped search payload with [count] results. */
    private fun searchJson(count: Int): String {
        val items = (1..count).joinToString(",") { i ->
            """{"trackId":$i,"trackName":"Track $i","artistName":"Daft Punk",""" +
                """"collectionName":"Discovery","artworkUrl100":"https://is1-ssl.mzstatic.com/100x100.jpg",""" +
                """"trackTimeMillis":${100L * i}}"""
        }
        return """{"resultCount":$count,"results":[$items]}"""
    }

    /** Deterministic single-result lookup payload. */
    private fun lookupJson(): String =
        """{"resultCount":1,"results":[{"trackId":1440913503,"trackName":"Harder, Better, Faster, Stronger",""" +
            """"artistName":"Daft Punk","collectionName":"Discovery",""" +
            """"artworkUrl100":"https://is1-ssl.mzstatic.com/100x100.jpg","trackTimeMillis":224000}]}"""

    private fun failingHandler(error: Throwable): MockRequestHandler = { throw error }
}
