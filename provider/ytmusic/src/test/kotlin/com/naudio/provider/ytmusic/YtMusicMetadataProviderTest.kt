package com.naudio.provider.ytmusic

import com.naudio.core.network.NetworkException
import com.naudio.provider.api.PageToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.net.ConnectException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic [MockEngine]-based tests over static JSON fixtures — the tests
 * never contact YouTube. The client under test uses the production-faithful
 * configuration (expectSuccess + the real network layer's retry/JSON policy
 * via [NaudioHttpClient.requestWithRetry] inside the provider).
 */
class YtMusicMetadataProviderTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/ytmusic/$name")!!.readBytes().decodeToString()

    private fun respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): MockRequestHandler = { respond(body, status, headersOf("Content-Type", "application/json")) }

    private fun provider(handler: MockRequestHandler): YtMusicMetadataProvider =
        YtMusicMetadataProvider(HttpClient(MockEngine(handler)) { expectSuccess = true })

    private fun requestBody(request: HttpRequestData): String =
        (request.body as? TextContent)?.text ?: error("expected a TextContent body")

    // ------------------------------------------------------------------
    // 1. search maps metadata from the initial page
    // ------------------------------------------------------------------
    @Test
    fun `search maps metadata from the initial page`() = runTest {
        val provider = provider(respondJson(fixture("initial_search.json")))

        val page = provider.searchTracks("rick astley")

        assertEquals(2, page.items.size)
        assertNull(page.nextToken)
        val first = page.items[0]
        assertEquals("dQw4w9WgXcQ", first.id)
        assertEquals("ytmusic", first.providerId)
        assertEquals("Never Gonna Give You Up", first.title)
        assertEquals("Rick Astley", first.artist)
        assertEquals("Whenever You Need Somebody", first.album)
        assertEquals("https://lh3.googleusercontent.com/cover-w544-h544", first.artworkUrl)
        assertEquals(213_000L, first.durationMs)
        val second = page.items[1]
        assertEquals("yPYZpwSpKmA", second.id)
        assertEquals("Together Forever", second.title)
        assertEquals(205_000L, second.durationMs)
    }

    // ------------------------------------------------------------------
    // 2. request construction: anonymous POST, web client contract
    // ------------------------------------------------------------------
    @Test
    fun `search request is an anonymous POST with the web client contract`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val provider = provider { request ->
            requests.add(request)
            respond(fixture("initial_search.json"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }

        provider.searchTracks("adele")

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("music.youtube.com", request.url.host)
        assertEquals("/youtubei/v1/search", request.url.encodedPath)
        // No authentication, cookies, or OAuth — anonymous public catalog access.
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["Cookie"])
        val body = requestBody(request)
        assertTrue(body.contains("\"query\":\"adele\""))
        assertTrue(body.contains("\"clientName\":\"WEB_REMIX\""))
        assertTrue(body.contains("\"params\":\"EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D\""))
        assertTrue(!body.contains("continuation"))
    }

    // ------------------------------------------------------------------
    // 3. continuation extraction from a paginated first page
    // ------------------------------------------------------------------
    @Test
    fun `search extracts the continuation token`() = runTest {
        val provider = provider(respondJson(fixture("search_with_continuation.json")))

        val page = provider.searchTracks("rick astley")

        assertEquals(2, page.items.size)
        assertEquals(PageToken.Opaque("opaque-continuation-token-1"), page.nextToken)
    }

    // ------------------------------------------------------------------
    // 4. full continuation round-trip: page 1 -> token -> page 2
    // ------------------------------------------------------------------
    @Test
    fun `continuation round-trip fetches and parses the second page`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val provider = provider { request ->
            requests.add(request)
            val body = requestBody(request)
            val fixture = if (body.contains("\"continuation\"")) {
                "continuation_page.json"
            } else {
                "search_with_continuation.json"
            }
            respond(fixture(fixture), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }

        val firstPage = provider.searchTracks("rick astley")
        val token = firstPage.nextToken
        assertEquals(PageToken.Opaque("opaque-continuation-token-1"), token)

        val secondPage = provider.searchTracks("rick astley", token = token)

        assertEquals(2, requests.size)
        assertEquals(1, secondPage.items.size)
        val track = secondPage.items.single()
        assertEquals("zx3Mc9Zq4PA", track.id)
        assertEquals("ytmusic", track.providerId)
        assertEquals("Cry For Help", track.title)
        assertEquals("Rick Astley", track.artist)
        assertEquals("Free", track.album)
        assertEquals("https://lh3.googleusercontent.com/free-w544-h544", track.artworkUrl)
        assertEquals(262_000L, track.durationMs)
        assertNull(secondPage.nextToken)
        // The continuation request reuses the token verbatim and drops the filter params.
        val secondBody = requestBody(requests[1])
        assertTrue(secondBody.contains("\"continuation\":\"opaque-continuation-token-1\""))
        assertTrue(!secondBody.contains("\"params\""))
    }

    // ------------------------------------------------------------------
    // 5. empty search result
    // ------------------------------------------------------------------
    @Test
    fun `search returns an empty page for zero results`() = runTest {
        val provider = provider(respondJson(fixture("empty_search.json")))

        val page = provider.searchTracks("zzzzz")

        assertTrue(page.items.isEmpty())
        assertNull(page.nextToken)
    }

    // ------------------------------------------------------------------
    // 6. malformed / changed response -> controlled serialization failure
    // ------------------------------------------------------------------
    @Test
    fun `malformed response fails as NetworkException Serialization`() = runTest {
        val provider = provider(respondJson(fixture("malformed_response.json")))

        assertFailsWith<NetworkException.Serialization> {
            provider.searchTracks("adele")
        }
    }

    // ------------------------------------------------------------------
    // 7. incompatible token type is rejected, never reinterpreted
    // ------------------------------------------------------------------
    @Test
    fun `an offset token is rejected instead of reinterpreted`() = runTest {
        val provider = provider(respondJson(fixture("initial_search.json")))

        val ex = assertFailsWith<IllegalArgumentException> {
            provider.searchTracks("adele", token = PageToken.Offset(10))
        }
        assertEquals(YtMusicMetadataProvider.TOKEN_TYPE_ERROR, ex.message)
    }

    // ------------------------------------------------------------------
    // 8. transport failures surface as the existing NetworkException types
    // ------------------------------------------------------------------
    @Test
    fun `network failure propagates as NetworkException Connectivity`() = runTest {
        val provider = provider { throw ConnectException("Connection refused") }

        val ex = assertFailsWith<NetworkException.Connectivity> {
            provider.searchTracks("adele")
        }
        assertEquals("Connection refused", ex.message)
    }

    @Test
    fun `http 5xx propagates as NetworkException HttpError`() = runTest {
        val provider = provider(
            respondJson(fixture("empty_search.json"), HttpStatusCode.BadGateway),
        )

        assertFailsWith<NetworkException.HttpError> {
            provider.searchTracks("adele")
        }
    }

    // ------------------------------------------------------------------
    // 9. cancellation is never swallowed
    // ------------------------------------------------------------------
    @Test
    fun `cancellation is not swallowed`() = runTest {
        val provider = provider { throw CancellationException("caller cancelled") }

        assertFailsWith<CancellationException> {
            provider.searchTracks("adele")
        }
    }

    // ------------------------------------------------------------------
    // 10. blank query short-circuits without a network call
    // ------------------------------------------------------------------
    @Test
    fun `blank search returns an empty page without a network call`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val provider = provider { request ->
            requests.add(request)
            respond(fixture("initial_search.json"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }

        val page = provider.searchTracks("   ")

        assertTrue(page.items.isEmpty())
        assertNull(page.nextToken)
        assertTrue(requests.isEmpty())
    }

    // ------------------------------------------------------------------
    // 11. duration parser edge cases
    // ------------------------------------------------------------------
    @Test
    fun `duration parsing handles hours and rejects junk`() {
        with(YtMusicResponseParser) {
            assertEquals(3_545_000L, "59:05".parseDurationMs())
            assertEquals(3_723_000L, "1:02:03".parseDurationMs())
            assertNull("abc".parseDurationMs())
            assertNull("".parseDurationMs())
        }
    }
}
