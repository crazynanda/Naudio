package com.naudio.provider.innertube.client

import com.naudio.core.network.NetworkException
import com.naudio.provider.innertube.Fixtures
import com.naudio.provider.innertube.request.InnerTubeRequest
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
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
 * The HTTP seam: endpoint targeting, the anonymous request shape, and the
 * failure behaviour every operation inherits from the shared network layer.
 */
class InnerTubeClientTest {

    private fun clientWith(handler: MockRequestHandler) =
        InnerTubeClient(Fixtures.client(handler))

    @Test
    fun `posts to the endpoint under the YouTube Music origin`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientWith { request ->
            requests.add(request)
            respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }

        client.execute(InnerTubeRequest.Search("adele"))

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("music.youtube.com", request.url.host)
        assertEquals(InnerTubeEndpoints.SEARCH, request.url.encodedPath)
        assertEquals("application/json", request.body.contentType?.withoutParameters()?.toString())
    }

    @Test
    fun `sends no authentication, cookies or user agent override`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = clientWith { request ->
            requests.add(request)
            respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }

        client.execute(InnerTubeRequest.Player("vid"))

        val headers = requests.single().headers
        assertNull(headers["Authorization"])
        assertNull(headers["Cookie"])
        assertNull(headers["X-Goog-Api-Key"])
        assertTrue(
            headers.names().none { it.equals("User-Agent", ignoreCase = true) },
            "the backend must not spoof a user agent",
        )
    }

    @Test
    fun `returns the raw response body verbatim`() = runTest {
        val client = clientWith(Fixtures.json("""{"contents":{"anything":1}}"""))

        val body = client.execute(InnerTubeRequest.Search("q"))

        assertEquals("""{"contents":{"anything":1}}""", body)
    }

    @Test
    fun `uses the injected client only and never creates one`() = runTest {
        // A 5xx proves the shared bounded retry runs (3 attempts), which is the
        // project's single retry policy rather than a backend-local one.
        var attempts = 0
        val client = clientWith {
            attempts++
            respond("nope", HttpStatusCode.BadGateway, headersOf("Content-Type", "application/json"))
        }

        assertFailsWith<NetworkException.HttpError> { client.execute(InnerTubeRequest.Search("q")) }
        assertEquals(3, attempts)
    }

    @Test
    fun `4xx is not retried`() = runTest {
        var attempts = 0
        val client = clientWith {
            attempts++
            respond("nope", HttpStatusCode.Forbidden, headersOf("Content-Type", "application/json"))
        }

        val ex = assertFailsWith<NetworkException.HttpError> {
            client.execute(InnerTubeRequest.Search("q"))
        }
        assertEquals(403, ex.code)
        assertEquals(1, attempts)
    }

    @Test
    fun `transport failure surfaces as NetworkException Connectivity`() = runTest {
        val client = clientWith(Fixtures.failing(ConnectException("Connection refused")))

        val ex = assertFailsWith<NetworkException.Connectivity> {
            client.execute(InnerTubeRequest.Search("q"))
        }
        assertEquals("Connection refused", ex.message)
    }

    @Test
    fun `cancellation propagates untouched and is never retried`() = runTest {
        var attempts = 0
        val client = clientWith {
            attempts++
            throw CancellationException("caller cancelled")
        }

        assertFailsWith<CancellationException> { client.execute(InnerTubeRequest.Search("q")) }
        assertEquals(1, attempts)
    }

    @Test
    fun `the base url can be redirected for tests without touching the endpoints`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = InnerTubeClient(
            Fixtures.client { request ->
                requests.add(request)
                respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            },
            baseUrl = "https://stub.invalid",
        )

        client.execute(InnerTubeRequest.Browse("UC1"))

        assertEquals("stub.invalid", requests.single().url.host)
        assertEquals(InnerTubeEndpoints.BROWSE, requests.single().url.encodedPath)
    }
}