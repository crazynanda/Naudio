package com.naudio.provider.ytmusic

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.core.network.NetworkException
import com.naudio.provider.api.PageToken
import com.naudio.provider.innertube.InnerTubeYtMusicBackend
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
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
 * Provider integration: the REAL [InnerTubeYtMusicBackend] behind the real
 * providers, with only the HTTP transport canned.
 *
 * The unit tests beside it use a fake backend to pin the providers' own logic;
 * this suite proves the two halves actually fit — that the InnerTube
 * implementation satisfies the interface the providers were written against, and
 * that its honest "no playable source" answer propagates all the way out as
 * `null` rather than a fabricated URL.
 */
class YtMusicProviderIntegrationTest {

    private fun backend(handler: MockRequestHandler) =
        InnerTubeYtMusicBackend(HttpClient(MockEngine(handler)) { expectSuccess = true })

    private fun bodyOf(request: HttpRequestData): String =
        (request.body as? TextContent)?.text ?: error("expected a TextContent body")

    private fun ytmTrack(id: String = "dQw4w9WgXcQ") =
        Track(id = id, providerId = "ytmusic", title = "T", artist = "A")

    /** Load one of the committed, realistic InnerTube fixtures. */
    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/ytmusic/$name")!!.readBytes().decodeToString()

    private fun MockRequestHandleScope.jsonResponse(body: String) =
        respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))

    // ------------------------------------------------------------------
    // Search through the whole stack
    // ------------------------------------------------------------------

    @Test
    fun `search travels the whole stack and returns domain tracks`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val provider = YtMusicMetadataProvider(backend { request ->
            seen.add(request)
            jsonResponse(fixture("initial_search.json"))
        })

        val page = provider.searchTracks("rick astley")

        assertEquals(2, page.items.size)
        assertEquals("dQw4w9WgXcQ", page.items[0].id)
        assertEquals("ytmusic", page.items[0].providerId)
        assertEquals("Never Gonna Give You Up", page.items[0].title)
        assertEquals(213_000L, page.items[0].durationMs)
        assertNull(page.nextToken)

        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/youtubei/v1/search", request.url.encodedPath)
        assertTrue(bodyOf(request).contains("\"query\":\"rick astley\""))
    }

    @Test
    fun `a continuation token round trips through the provider`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val provider = YtMusicMetadataProvider(backend { request ->
            seen.add(request)
            val body = if (bodyOf(request).contains("\"continuation\"")) {
                fixture("continuation_page.json")
            } else {
                fixture("search_with_continuation.json")
            }
            jsonResponse(body)
        })

        val first = provider.searchTracks("q")
        assertEquals(PageToken.Opaque("opaque-continuation-token-1"), first.nextToken)

        provider.searchTracks("q", token = first.nextToken)

        assertEquals(2, seen.size)
        val secondBody = bodyOf(seen[1])
        assertTrue(secondBody.contains("\"continuation\":\"opaque-continuation-token-1\""))
        assertTrue(!secondBody.contains("\"params\""), "a continuation drops the first-page filter")
    }

    @Test
    fun `an offset token still fails before any request is made`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val provider = YtMusicMetadataProvider(backend { request ->
            seen.add(request)
            respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })

        assertFailsWith<IllegalArgumentException> {
            provider.searchTracks("q", token = PageToken.Offset(5))
        }
        assertTrue(seen.isEmpty())
    }

    // ------------------------------------------------------------------
    // Playback: the honest-outcome path, end to end
    // ------------------------------------------------------------------

    @Test
    fun `a directly playable stream resolves end to end`() = runTest {
        val provider = YtMusicPlaybackProvider(
            backend {
                respond(
                    """
                    {"playabilityStatus":{"status":"OK"},
                     "streamingData":{"adaptiveFormats":[
                       {"mimeType":"audio/mp4","bitrate":128000,
                        "url":"https://rr3---sn-direct.example/audio"}
                     ]}}
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            },
        )

        assertEquals(AudioSource.Remote("https://rr3---sn-direct.example/audio"), provider.resolve(ytmTrack()))
    }

    @Test
    fun `a ciphered stream resolves to null end to end`() = runTest {
        val provider = YtMusicPlaybackProvider(
            backend {
                respond(
                    """
                    {"playabilityStatus":{"status":"OK"},
                     "streamingData":{"adaptiveFormats":[
                       {"mimeType":"audio/webm","bitrate":133499,
                        "signatureCipher":"s=SIG&sp=sig&url=https%3A%2F%2Fciphered.example%2Fa"}
                     ]}}
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            },
        )

        assertNull(provider.resolve(ytmTrack()))
    }

    @Test
    fun `a not-entitled stream resolves to null end to end`() = runTest {
        val provider = YtMusicPlaybackProvider(
            backend {
                respond(
                    """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"}}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            },
        )

        assertNull(provider.resolve(ytmTrack()))
    }

    @Test
    fun `an iTunes track never reaches the YouTube Music backend`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val provider = YtMusicPlaybackProvider(backend { request ->
            seen.add(request)
            respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })

        val source = provider.resolve(
            Track(id = "1440913503", providerId = "itunes", title = "T", artist = "A"),
        )

        assertNull(source)
        assertTrue(seen.isEmpty(), "exact routing must stop the lookup before the network")
    }

    // ------------------------------------------------------------------
    // Failure propagation
    // ------------------------------------------------------------------

    @Test
    fun `a transport failure surfaces through the provider`() = runTest {
        val provider = YtMusicMetadataProvider(backend { throw ConnectException("Connection refused") })

        val ex = assertFailsWith<NetworkException.Connectivity> { provider.searchTracks("q") }
        assertEquals("Connection refused", ex.message)
    }

    @Test
    fun `a malformed response surfaces as a serialization failure`() = runTest {
        val provider = YtMusicMetadataProvider(
            backend { respond(fixture("malformed_response.json")) },
        )

        assertFailsWith<NetworkException.Serialization> { provider.searchTracks("q") }
    }

    @Test
    fun `an http error surfaces as NetworkException HttpError`() = runTest {
        val provider = YtMusicMetadataProvider(
            backend {
                respond(
                    """{"error":"nope"}""",
                    HttpStatusCode.BadGateway,
                    headersOf("Content-Type", "application/json"),
                )
            },
        )

        assertFailsWith<NetworkException.HttpError> { provider.searchTracks("q") }
    }

    @Test
    fun `cancellation propagates through both providers`() = runTest {
        val failing = backend { throw CancellationException("caller cancelled") }

        assertFailsWith<CancellationException> {
            YtMusicMetadataProvider(failing).searchTracks("q")
        }
        assertFailsWith<CancellationException> {
            YtMusicPlaybackProvider(failing).resolve(ytmTrack())
        }
    }

    // ------------------------------------------------------------------
    // lookupTrack end to end
    // ------------------------------------------------------------------

    @Test
    fun `lookupTrack resolves real metadata through the watch page`() = runTest {
        val provider = YtMusicMetadataProvider(
            backend {
                respond(
                    """
                    {"videoDetails":{"videoId":"dQw4w9WgXcQ","title":"Never Gonna Give You Up",
                      "author":"Rick Astley","lengthSeconds":"213"}}
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            },
        )

        val track = provider.lookupTrack("dQw4w9WgXcQ")

        assertEquals("Never Gonna Give You Up", track?.title)
        assertEquals("Rick Astley", track?.artist)
        assertEquals(213_000L, track?.durationMs)
    }

    @Test
    fun `lookupTrack reports an unknown id as null`() = runTest {
        val provider = YtMusicMetadataProvider(
            backend {
                respond("""{"contents":{}}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            },
        )

        assertNull(provider.lookupTrack("nope"))
    }
}