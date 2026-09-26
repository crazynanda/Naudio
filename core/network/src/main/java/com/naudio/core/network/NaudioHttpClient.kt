package com.naudio.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.math.min

/**
 * Application-lifetime Ktor HTTP client for naudio.
 *
 * - One shared [HttpClient] per process: create once via [create] and reuse it
 *   for every request. Never construct an HttpClient per request.
 * - JSON via ContentNegotiation (kotlinx.serialization), registered for
 *   ContentType.Application.Json. Provider DTOs live in provider modules —
 *   this object deliberately knows nothing about domain models.
 * - Timeouts: connect 10 s, request 15 s, socket 15 s.
 * - Retry: bounded exponential backoff for transient failures only —
 *   [NetworkException.Timeout], [NetworkException.Connectivity],
 *   [NetworkException.Serialization], [NetworkException.Unknown], and 5xx
 *   [NetworkException.HttpError]. 4xx (including auth failures) and
 *   cancellation are never retried; cancellation propagates untouched.
 */
object NaudioHttpClient {

    /** Maximum number of attempts per request — 3 TOTAL: 1 initial + 2 retries. */
    internal const val MAX_ATTEMPTS = 3

    /** Base delay in milliseconds for exponential backoff (0.5 s, 1 s, 2 s, …). */
    internal const val BASE_DELAY_MS = 500L

    /** Upper bound for a single backoff delay. */
    internal const val MAX_DELAY_MS = 10_000L

    /**
     * Shared JSON instance for provider DTOs: tolerate unknown fields (APIs
     * evolve), accept loose JSON, and treat absent nullable fields as null.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** Create the shared application-lifetime client (OkHttp engine). */
    fun create(): HttpClient = HttpClient(OkHttp) { configure() }

    /** Alias for [create]; kept for DI call sites. */
    fun createDefault(): HttpClient = create()

    /**
     * Test seam: create a client on an injected engine (e.g. MockEngine) with
     * the exact production configuration. Not part of the public API surface.
     */
    internal fun create(engine: HttpClientEngine): HttpClient = HttpClient(engine) { configure() }

    /**
     * Run a suspend request block with bounded exponential backoff.
     *
     * Retries only transient failures: Timeout, Connectivity, Serialization,
     * Unknown, and 5xx HttpError. 4xx HttpError and CancellationException
     * rethrow immediately. Makes at most [maxAttempts] attempts TOTAL
     * (default 3 = 1 initial + 2 retries).
     */
    suspend fun <T> requestWithRetry(
        maxAttempts: Int = MAX_ATTEMPTS,
        block: suspend () -> T,
    ): T = retryWithBackoff(maxAttempts = maxAttempts, block = block)

    /**
     * Internal, testable retry core. [delayFn] is injectable so unit tests run
     * on virtual time with no real sleeps.
     *
     * On permanent failure the [NetworkException] mapped from the last
     * underlying error is thrown, with the original error preserved as its
     * cause (see [Throwable.toNetworkException]).
     */
    internal suspend fun <T> retryWithBackoff(
        maxAttempts: Int = MAX_ATTEMPTS,
        baseDelayMs: Long = BASE_DELAY_MS,
        delayFn: suspend (Long) -> Unit = { delay(it) },
        block: suspend () -> T,
    ): T {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        repeat(maxAttempts) { attemptIndex ->
            try {
                return block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                val mapped = t.toNetworkException()
                val hasAttemptsLeft = attemptIndex < maxAttempts - 1
                val isTransient = mapped is NetworkException.Timeout ||
                    mapped is NetworkException.Connectivity ||
                    mapped is NetworkException.Serialization ||
                    mapped is NetworkException.Unknown ||
                    (mapped is NetworkException.HttpError && mapped.isRetryable())
                if (!hasAttemptsLeft || !isTransient) throw mapped
                delayFn(backoffDelayMs(baseDelayMs, attemptIndex + 1))
            }
        }
        error("retryWithBackoff exhausted without throwing")
    }

    /** Exponential backoff for the nth retry (1-based), capped at [MAX_DELAY_MS]. */
    internal fun backoffDelayMs(baseDelayMs: Long, retryNumber: Int): Long =
        min(baseDelayMs * (1L shl (retryNumber - 1)), MAX_DELAY_MS)

    /** Shared client configuration, applied identically to every engine. */
    private fun HttpClientConfig<*>.configure() {
        expectSuccess = true
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }
}
