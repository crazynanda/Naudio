package com.naudio.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NaudioHttpClientTest {

    @Serializable
    data class IdDto(val id: Int)

    @Serializable
    data class LooseDto(val id: Int, val name: String? = null)

    // ------------------------------------------------------------------
    // 1. Successful JSON response
    // ------------------------------------------------------------------
    @Test
    fun `successful JSON response returns body`() = runTest {
        val client = clientWith(constantHandler(HttpStatusCode.OK, """{"id":1}"""))
        val response = client.get("https://api.test/v1/items")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"id":1}""", response.bodyAsText())
        client.close()
    }

    // ------------------------------------------------------------------
    // 2. JSON round-trip through ContentNegotiation
    // ------------------------------------------------------------------
    @Test
    fun `JSON deserializes typed DTO via ContentNegotiation`() = runTest {
        val client = clientWith(constantHandler(HttpStatusCode.OK, """{"id":7}"""))
        val dto: IdDto = client.post("https://api.test/v1/items") {
            contentType(Json)
            setBody(IdDto(id = 7))
        }.body()
        assertEquals(7, dto.id)
        client.close()
    }

    @Test
    fun `JSON serializes request body via ContentNegotiation`() = runTest {
        val client = clientWith { request ->
            assertEquals(Json, request.body.contentType)
            respond("""{"id":7}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val dto: IdDto = client.post("https://api.test/v1/items") {
            contentType(Json)
            setBody(IdDto(id = 7))
        }.body()
        assertEquals(7, dto.id)
        client.close()
    }

    // ------------------------------------------------------------------
    // 3. Unknown fields tolerated
    // ------------------------------------------------------------------
    @Test
    fun `unknown JSON fields are ignored`() = runTest {
        val client = clientWith(constantHandler(HttpStatusCode.OK, """{"id":3,"surprise":"extra","nested":{"a":1}}"""))
        val dto: LooseDto = client.get("https://api.test/v1/items").body()
        assertEquals(3, dto.id)
        assertEquals(null, dto.name)
        client.close()
    }

    // ------------------------------------------------------------------
    // 4. 4xx → HttpError, NOT retried
    // ------------------------------------------------------------------
    @Test
    fun `404 becomes HttpError and is not retried`() = runTest {
        val attempts = intCounter()
        val client = clientWith { _ ->
            attempts.increment()
            respond("""{"error":"not found"}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val ex = assertFailsWith<NetworkException.HttpError> {
            NaudioHttpClient.requestWithRetry { client.get("https://api.test/v1/items") }
        }
        assertEquals(404, ex.code)
        assertEquals(1, attempts.get())
        client.close()
    }

    @Test
    fun `401 authentication failure is not retried`() = runTest {
        val attempts = intCounter()
        val client = clientWith { _ ->
            attempts.increment()
            respond("unauthorized", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val ex = assertFailsWith<NetworkException.HttpError> {
            NaudioHttpClient.requestWithRetry { client.get("https://api.test/v1/me") }
        }
        assertEquals(401, ex.code)
        assertEquals(1, attempts.get())
        client.close()
    }

    // ------------------------------------------------------------------
    // 5. 5xx retries and eventually succeeds
    // ------------------------------------------------------------------
    @Test
    fun `503 then success retries and returns body`() = runTest {
        val attempts = intCounter()
        val client = clientWith { _ ->
            if (attempts.increment() == 1) {
                respond("overloaded", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond("""{"id":9}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val body = NaudioHttpClient.requestWithRetry { client.get("https://api.test/v1/items").bodyAsText() }
        assertEquals("""{"id":9}""", body)
        assertEquals(2, attempts.get())
        client.close()
    }

    // ------------------------------------------------------------------
    // 6. Bounded: exactly 3 TOTAL attempts
    // ------------------------------------------------------------------
    @Test
    fun `persistent 5xx stops after exactly 3 total attempts`() = runTest {
        val attempts = intCounter()
        val client = clientWith { _ ->
            attempts.increment()
            respond("overloaded", HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val ex = assertFailsWith<NetworkException.HttpError> {
            NaudioHttpClient.requestWithRetry { client.get("https://api.test/v1/items") }
        }
        assertEquals(502, ex.code)
        assertEquals(3, attempts.get())
        client.close()
    }

    @Test
    fun `persistent timeout stops after exactly 3 total attempts`() = runTest {
        val attempts = intCounter()
        val client = clientWith(failingHandler(SocketTimeoutException("read timed out")))
        runCatching {
            NaudioHttpClient.requestWithRetry(maxAttempts = 3) {
                attempts.increment()
                client.get("https://api.test/v1/items")
            }
        }
        assertEquals(3, attempts.get())
        client.close()
    }

    @Test
    fun `custom maxAttempts is honored`() = runTest {
        val attempts = intCounter()
        val client = clientWith { _ ->
            attempts.increment()
            respond("nope", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        assertFailsWith<NetworkException.HttpError> {
            NaudioHttpClient.requestWithRetry(maxAttempts = 5) { client.get("https://api.test/v1/items") }
        }
        assertEquals(5, attempts.get())
        client.close()
    }

    // ------------------------------------------------------------------
    // 7. Timeout maps correctly
    // ------------------------------------------------------------------
    @Test
    fun `socket timeout maps to NetworkException Timeout`() = runTest {
        val client = clientWith(failingHandler(SocketTimeoutException("read timed out")))
        val ex = assertFailsWith<NetworkException.Timeout> {
            NaudioHttpClient.requestWithRetry(maxAttempts = 1) { client.get("https://api.test/v1/items") }
        }
        assertTrue(ex.message.orEmpty().contains("timed out"))
        client.close()
    }

    @Test
    fun `request timeout exception maps to NetworkException Timeout`() = runTest {
        val client = clientWith(failingHandler(HttpRequestTimeoutException("request timeout", null, null)))
        val ex = assertFailsWith<NetworkException.Timeout> {
            NaudioHttpClient.requestWithRetry(maxAttempts = 1) { client.get("https://api.test/v1/items") }
        }
        assertTrue(ex.message.orEmpty().contains("timeout"))
        client.close()
    }

    // ------------------------------------------------------------------
    // 8. Connectivity / engine failure maps correctly
    // ------------------------------------------------------------------
    @Test
    fun `unknown host maps to NetworkException Connectivity`() = runTest {
        val client = clientWith(failingHandler(UnknownHostException("api.test")))
        val ex = assertFailsWith<NetworkException.Connectivity> {
            NaudioHttpClient.requestWithRetry(maxAttempts = 1) { client.get("https://api.test/v1/items") }
        }
        assertEquals("api.test", ex.message)
        client.close()
    }

    @Test
    fun `connection refused maps to NetworkException Connectivity`() = runTest {
        val client = clientWith(failingHandler(ConnectException("Connection refused")))
        val ex = assertFailsWith<NetworkException.Connectivity> {
            NaudioHttpClient.requestWithRetry(maxAttempts = 1) { client.get("https://api.test/v1/items") }
        }
        assertEquals("Connection refused", ex.message)
        client.close()
    }

    @Test
    fun `malformed body maps to NetworkException Serialization`() = runTest {
        val client = clientWith(constantHandler(HttpStatusCode.OK, """{"id": "not-an-int"}"""))
        val ex = assertFailsWith<NetworkException.Serialization> {
            NaudioHttpClient.requestWithRetry<IdDto>(maxAttempts = 1) { client.get("https://api.test/v1/items").body() }
        }
        assertTrue(ex.message.orEmpty().contains("not-an-int"))
        client.close()
    }

    @Test
    fun `unknown engine failure maps to NetworkException Unknown`() = runTest {
        val client = clientWith(failingHandler(IllegalStateException("boom")))
        val ex = assertFailsWith<NetworkException.Unknown> {
            NaudioHttpClient.requestWithRetry(maxAttempts = 1) { client.get("https://api.test/v1/items") }
        }
        assertEquals("boom", ex.message)
        client.close()
    }

    // ------------------------------------------------------------------
    // Cancellation is never retried and propagates untouched
    // ------------------------------------------------------------------
    @Test
    fun `cancellation propagates and is not retried`() = runTest {
        val attempts = intCounter()
        val client = clientWith { _ ->
            attempts.increment()
            throw CancellationException("caller cancelled")
        }
        assertFailsWith<CancellationException> {
            NaudioHttpClient.requestWithRetry { client.get("https://api.test/v1/items") }
        }
        assertEquals(1, attempts.get())
        client.close()
    }

    // ------------------------------------------------------------------
    // 9. Backoff is exponential and capped; no excessive runtime
    // ------------------------------------------------------------------
    @Test
    fun `backoff delays are exponential and capped`() = runTest {
        val recorded = mutableListOf<Long>()
        var attempts = 0
        val result = NaudioHttpClient.retryWithBackoff(
            maxAttempts = 4,
            baseDelayMs = 500,
            delayFn = { recorded.add(it) },
        ) {
            attempts++
            if (attempts < 4) throw java.io.IOException("transient")
            42
        }
        assertEquals(42, result)
        assertEquals(4, attempts)
        assertEquals(listOf(500L, 1000L, 2000L), recorded)
    }

    @Test
    fun `backoff caps at 10 seconds`() = runTest {
        assertEquals(500L, NaudioHttpClient.backoffDelayMs(500, 1))
        assertEquals(1000L, NaudioHttpClient.backoffDelayMs(500, 2))
        assertEquals(2000L, NaudioHttpClient.backoffDelayMs(500, 3))
        assertEquals(4000L, NaudioHttpClient.backoffDelayMs(500, 4))
        assertEquals(8000L, NaudioHttpClient.backoffDelayMs(500, 5))
        assertEquals(10_000L, NaudioHttpClient.backoffDelayMs(500, 6))
        assertEquals(10_000L, NaudioHttpClient.backoffDelayMs(500, 20))
    }

    @Test
    fun `retry backoff sleeps between attempts then succeeds`() = runTest {
        val attempts = intCounter()
        var thrown = 0
        val client = clientWith { _ ->
            if (attempts.increment() < 3) {
                thrown++
                respond("overloaded", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond("""{"id":1}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        // runTest virtual time: the two backoff delays (500 + 1000 ms) advance
        // instantly, so this path completes with no real sleeps.
        val body = NaudioHttpClient.requestWithRetry { client.get("https://api.test/v1/items").bodyAsText() }
        assertEquals("""{"id":1}""", body)
        assertEquals(2, thrown)
        assertEquals(3, attempts.get())
        client.close()
    }

    // ------------------------------------------------------------------
    // 10. Shared client executes multiple requests
    // ------------------------------------------------------------------
    @Test
    fun `shared client runs multiple sequential requests`() = runTest {
        val attempts = intCounter()
        val client = clientWith { _ ->
            val n = attempts.increment()
            respond("""{"id":$n}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val first: IdDto = client.get("https://api.test/v1/items").body()
        val second: IdDto = client.get("https://api.test/v1/items").body()
        assertEquals(1, first.id)
        assertEquals(2, second.id)
        assertEquals(2, attempts.get())
        client.close()
    }

    @Test
    fun `shared client runs concurrent requests`() = runTest {
        val client = clientWith(constantHandler(HttpStatusCode.OK, """{"id":1}"""))
        val deferreds = (1..8).map { async { client.get("https://api.test/v1/items").body<IdDto>() } }
        val results = deferreds.map { it.await() }
        assertEquals(8, results.size)
        assertTrue(results.all { it.id == 1 })
        client.close()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Production-identical client on a MockEngine (internal test seam). */
    private fun clientWith(handler: MockRequestHandler): HttpClient =
        NaudioHttpClient.create(MockEngine(handler))

    private fun intCounter(): SimpleCounter = SimpleCounter()

    private class SimpleCounter {
        private var value = 0
        fun increment(): Int = ++value
        fun get(): Int = value
    }
}
