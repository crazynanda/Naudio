package com.naudio.provider.itunes

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.core.network.NetworkException
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic [MockEngine]-based tests: no real network access, no URL is
 * ever fabricated — the provider may only return the API-served previewUrl.
 */
class ItunesPlaybackProviderTest {

    private fun clientWith(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) { expectSuccess = true }

    private fun provider(handler: MockRequestHandler): ItunesPlaybackProvider =
        ItunesPlaybackProvider(clientWith(handler))

    private fun itunesTrack(id: String = "1440913503"): Track =
        Track(id = id, providerId = "itunes", title = "T", artist = "A")

    // ------------------------------------------------------------------
    // 1. preview URL successfully resolves to AudioSource.Remote
    // ------------------------------------------------------------------
    @Test
    fun `resolve returns remote preview url on lookup hit`() = runTest {
        val provider = provider(respondJson(lookupJson(preview = true)))
        val source = provider.resolve(itunesTrack())
        assertEquals(
            AudioSource.Remote("https://audio-ssl.itunes.apple.com/preview.m4a"),
            source,
        )
    }

    // ------------------------------------------------------------------
    // 2. missing preview URL returns null (entry exists, no playable stream)
    // ------------------------------------------------------------------
    @Test
    fun `resolve returns null when preview url is missing`() = runTest {
        val provider = provider(respondJson(lookupJson(preview = false)))
        assertNull(provider.resolve(itunesTrack()))
    }

    // ------------------------------------------------------------------
    // 3. unknown track returns null (iTunes reports miss as 200 + 0 results)
    // ------------------------------------------------------------------
    @Test
    fun `resolve returns null for unknown track`() = runTest {
        val provider = provider(respondJson("""{"resultCount":0,"results":[]}"""))
        assertNull(provider.resolve(itunesTrack("999999999")))
    }

    // ------------------------------------------------------------------
    // 4. foreign track / blank id -> null without any network request
    // ------------------------------------------------------------------
    @Test
    fun `resolve returns null for a track from another provider without a request`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val provider = ItunesPlaybackProvider(
            clientWith { request ->
                requests.add(request)
                respond(lookupJson(preview = true), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            },
        )
        val foreign = Track(id = "42", providerId = "local", title = "T", artist = "A")
        assertNull(provider.resolve(foreign))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `resolve returns null for blank track id without a request`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val provider = ItunesPlaybackProvider(
            clientWith { request ->
                requests.add(request)
                respond(lookupJson(preview = true), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            },
        )
        assertNull(provider.resolve(itunesTrack(id = "  ")))
        assertTrue(requests.isEmpty())
    }

    // ------------------------------------------------------------------
    // 5. the request goes to /lookup with the track id (never /search, never derived)
    // ------------------------------------------------------------------
    @Test
    fun `resolve requests the lookup endpoint with the track id`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val provider = ItunesPlaybackProvider(
            clientWith { request ->
                requests.add(request)
                respond(lookupJson(preview = true), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            },
        )
        provider.resolve(itunesTrack("1440913503"))
        val url = requests.single().url
        assertEquals("https", url.protocol.name)
        assertEquals("itunes.apple.com", url.host)
        assertEquals("/lookup", url.encodedPath)
        assertEquals("1440913503", url.parameters["id"])
    }

    // ------------------------------------------------------------------
    // 6. malformed response surfaces as NetworkException.Serialization
    // ------------------------------------------------------------------
    @Test
    fun `malformed response surfaces as NetworkException Serialization`() = runTest {
        val provider = provider(respondJson("""{"resultCount":1,"results":[{"trackId":}]}"""))
        assertFailsWith<NetworkException.Serialization> {
            provider.resolve(itunesTrack())
        }
    }

    @Test
    fun `network failure propagates as NetworkException Connectivity`() = runTest {
        val provider = provider(failingHandler(ConnectException("Connection refused")))
        val ex = assertFailsWith<NetworkException.Connectivity> {
            provider.resolve(itunesTrack())
        }
        assertEquals("Connection refused", ex.message)
    }

    // ------------------------------------------------------------------
    // 7. cancellation propagates untouched
    // ------------------------------------------------------------------
    @Test
    fun `cancellation is not swallowed`() = runTest {
        val provider = provider(failingHandler(CancellationException("caller cancelled")))
        assertFailsWith<CancellationException> {
            provider.resolve(itunesTrack())
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): MockRequestHandler = { respond(body, status, headersOf("Content-Type", "application/json")) }

    /** Deterministic single-result lookup payload, with/without a previewUrl. */
    private fun lookupJson(preview: Boolean): String {
        val previewField = if (preview) {
            ""","previewUrl":"https://audio-ssl.itunes.apple.com/preview.m4a""""
        } else {
            ""
        }
        return """{"resultCount":1,"results":[{"trackId":1440913503,"trackName":"Harder, Better, Faster, Stronger",""" +
            """"artistName":"Daft Punk","collectionName":"Discovery",""" +
            """"artworkUrl100":"https://is1-ssl.mzstatic.com/100x100.jpg","trackTimeMillis":224000$previewField}]}"""
    }

    private fun failingHandler(error: Throwable): MockRequestHandler = { throw error }
}
