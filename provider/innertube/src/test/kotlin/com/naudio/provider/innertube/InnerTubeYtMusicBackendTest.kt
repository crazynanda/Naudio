package com.naudio.provider.innertube

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.core.network.NetworkException
import com.naudio.provider.api.PageToken
import com.naudio.provider.innertube.api.YtMusicBackend
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
 * End-to-end tests over the REAL backend stack — client, request builders and
 * parsers — with only the HTTP transport canned. These are what prove the
 * boundary is wired correctly, rather than merely well-formed in isolation.
 */
class InnerTubeYtMusicBackendTest {

    private fun backend(handler: MockRequestHandler) =
        InnerTubeYtMusicBackend(Fixtures.client(handler))

    /** Records every request and answers each with [body]. */
    private fun recorder(body: String): Pair<MutableList<HttpRequestData>, MockRequestHandler> {
        val seen = mutableListOf<HttpRequestData>()
        return seen to { request ->
            seen.add(request)
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
    }

    private fun track(
        id: String,
        providerId: String = YtMusicBackend.PROVIDER_ID,
    ) = Track(id = id, providerId = providerId, title = "T", artist = "A")

    private fun browseIdOf(request: HttpRequestData): String? =
        Regex("\"browseId\":\"([^\"]+)\"")
            .find(Fixtures.bodyOf(request))
            ?.groupValues
            ?.get(1)

    // ------------------------------------------------------------------
    // Search + pagination
    // ------------------------------------------------------------------

    @Test
    fun `search returns a page of tracks and a cursor`() = runTest {
        val backend = backend(Fixtures.json(Fixtures.load("search_page.json")))

        val page = backend.search("rick astley")

        assertEquals(2, page.items.size)
        assertEquals(PageToken.Opaque("CONT-TOKEN-1"), page.nextToken)
    }

    @Test
    fun `a continuation round trip drives a second request`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val backend = backend { request ->
            seen.add(request)
            val isContinuation = Fixtures.bodyOf(request).contains("\"continuation\"")
            respond(
                if (isContinuation) Fixtures.load("search_page_2.json") else Fixtures.load("search_page.json"),
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }

        val first = backend.search("rick astley")
        val second = backend.search("rick astley", continuation = (first.nextToken as PageToken.Opaque).value)

        assertEquals(2, seen.size)
        assertEquals(listOf("zx3Mc9Zq4PA"), second.items.map { it.id })
        assertNull(second.nextToken)
    }

    @Test
    fun `a blank query never reaches the network`() = runTest {
        val (seen, handler) = recorder("{}")
        val backend = backend(handler)

        val result = backend.search("   ")

        assertTrue(result.items.isEmpty())
        assertNull(result.nextToken)
        assertTrue(seen.isEmpty())
    }

    // ------------------------------------------------------------------
    // Home / song / artist / album / playlist / related
    // ------------------------------------------------------------------

    @Test
    fun `home is fetched from the home browse id`() = runTest {
        val (seen, handler) = recorder(Fixtures.load("home.json"))
        val backend = backend(handler)

        val home = backend.home()

        assertEquals(2, home.shelves.size)
        assertEquals("/youtubei/v1/browse", seen.single().url.encodedPath)
        assertEquals("FEmusic_home", browseIdOf(seen.single()))
    }

    @Test
    fun `song resolves a single track`() = runTest {
        val backend = backend(Fixtures.json(Fixtures.load("watch.json")))

        val track = backend.song("dQw4w9WgXcQ")

        assertEquals("dQw4w9WgXcQ", track?.id)
        assertEquals("Rick Astley", track?.artist)
    }

    @Test
    fun `a blank song id never reaches the network`() = runTest {
        val (seen, handler) = recorder("{}")
        val backend = backend(handler)

        assertNull(backend.song(""))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `artist album and playlist each browse their own id`() = runTest {
        val (seen, handler) = recorder(Fixtures.load("artist.json"))
        val backend = backend(handler)

        backend.artist("UCrick")
        backend.album("MPREb_AlbumOne")
        backend.playlist("VLPLabc")

        val ids = seen.mapNotNull(::browseIdOf)
        assertEquals(listOf("UCrick", "MPREb_AlbumOne", "VLPLabc"), ids)
    }

    @Test
    fun `an artist continuation is sent as the browse cursor`() = runTest {
        val (seen, handler) = recorder(Fixtures.load("artist.json"))
        val backend = backend(handler)

        backend.artist("UCrick", continuation = "ARTIST-TOKEN-1")

        assertTrue(Fixtures.bodyOf(seen.single()).contains("ARTIST-TOKEN-1"))
    }

    @Test
    fun `related returns the watch page rail`() = runTest {
        val backend = backend(Fixtures.json(Fixtures.load("watch.json")))

        val page = backend.related("dQw4w9WgXcQ")

        assertEquals(listOf("yPYZpwSpKmA", "zx3Mc9Zq4PA"), page.items.map { it.id })
    }

    @Test
    fun `an unusable related cursor ends paging instead of being replayed`() = runTest {
        val (seen, handler) = recorder(Fixtures.load("watch.json"))
        val backend = backend(handler)

        val page = backend.related("dQw4w9WgXcQ", continuation = "A-CURSOR-THE-NEXT-ENDPOINT-CANNOT-USE")

        assertTrue(page.items.isNotEmpty())
        assertNull(page.nextToken)
        assertEquals(1, seen.size)
    }

    @Test
    fun `a blank related id never reaches the network`() = runTest {
        val (seen, handler) = recorder("{}")
        val backend = backend(handler)

        assertTrue(backend.related("").items.isEmpty())
        assertTrue(seen.isEmpty())
    }

    // ------------------------------------------------------------------
    // Playback resolution — the honest-outcome contract
    // ------------------------------------------------------------------

    @Test
    fun `a directly playable stream resolves to a remote source`() = runTest {
        val backend = backend(Fixtures.json(Fixtures.load("player_direct.json")))

        val source = backend.resolvePlayback(track("dQw4w9WgXcQ"))

        assertEquals(AudioSource.Remote("https://rr3---sn-direct-audio.example/mp4-m4a"), source)
    }

    @Test
    fun `a ciphered stream resolves to null and never to a deciphered url`() = runTest {
        val backend = backend(Fixtures.json(Fixtures.load("player_ciphered.json")))

        assertNull(backend.resolvePlayback(track("dQw4w9WgXcQ")))
    }

    @Test
    fun `a non-entitled stream resolves to null`() = runTest {
        val backend = backend(Fixtures.json(Fixtures.load("player_not_entitled.json")))

        assertNull(backend.resolvePlayback(track("dQw4w9WgXcQ")))
    }

    @Test
    fun `a track from another provider is never resolved`() = runTest {
        val (seen, handler) = recorder("{}")
        val backend = backend(handler)

        val source = backend.resolvePlayback(track("1", providerId = "itunes"))

        assertNull(source)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a blank track id is never resolved`() = runTest {
        val (seen, handler) = recorder("{}")
        val backend = backend(handler)

        assertNull(backend.resolvePlayback(track("")))
        assertTrue(seen.isEmpty())
    }

    // ------------------------------------------------------------------
    // Failure behaviour inherited from the client
    // ------------------------------------------------------------------

    @Test
    fun `a transport failure surfaces as NetworkException`() = runTest {
        val backend = backend(Fixtures.failing(ConnectException("Connection refused")))

        assertFailsWith<NetworkException.Connectivity> { backend.search("q") }
    }

    @Test
    fun `cancellation propagates untouched from every operation`() = runTest {
        val backend = backend(Fixtures.failing(CancellationException("caller cancelled")))

        assertFailsWith<CancellationException> { backend.search("q") }
        assertFailsWith<CancellationException> { backend.home() }
        assertFailsWith<CancellationException> { backend.song("v") }
        assertFailsWith<CancellationException> { backend.artist("UC1") }
        assertFailsWith<CancellationException> { backend.album("MPREb_x") }
        assertFailsWith<CancellationException> { backend.playlist("VLPLx") }
        assertFailsWith<CancellationException> { backend.related("v") }
        assertFailsWith<CancellationException> { backend.resolvePlayback(track("v")) }
    }

    @Test
    fun `a malformed search body surfaces as a serialization failure`() = runTest {
        val backend = backend(Fixtures.json("""{"contents":{"someFutureRenderer":{}}}"""))

        assertFailsWith<NetworkException.Serialization> { backend.search("q") }
    }

    // ------------------------------------------------------------------
    // Boundary
    // ------------------------------------------------------------------

    @Test
    fun `the backend is usable through the abstraction alone`() = runTest {
        val backend: YtMusicBackend =
            InnerTubeYtMusicBackend(Fixtures.client(Fixtures.json(Fixtures.load("search_page.json"))))

        assertEquals("ytmusic", YtMusicBackend.PROVIDER_ID)
        assertEquals(2, backend.search("q").items.size)
    }
}