package com.naudio.provider.innertube.client

import com.naudio.core.network.NaudioHttpClient
import com.naudio.provider.innertube.request.InnerTubeRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.contentType

/**
 * The single HTTP seam of the InnerTube backend.
 *
 * Every network call the YT Music integration makes goes through [execute].
 * Nothing above this class touches Ktor, and nothing outside the backend can
 * construct or reach it (it is `internal`).
 *
 * Deliberate properties:
 *  - The shared, application-lifetime [HttpClient] is injected; this class
 *    never creates a second client and never closes the one it is given.
 *  - Retries come from the project's existing bounded backoff
 *    ([NaudioHttpClient.requestWithRetry]) — no second retry policy.
 *  - The raw JSON body is sent via `TextContent` because the shared client's
 *    ContentNegotiation plugin would otherwise re-encode a plain String body.
 *  - Responses are returned as raw text and decoded by the parsers, which need
 *    a JSON tree rather than a typed DTO.
 *  - The response is not interpreted here: "200 but nothing playable" is a
 *    normal outcome, not an error, and the parsers decide what it means.
 */
internal class InnerTubeClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = InnerTubeEndpoints.BASE_URL,
) {

    /**
     * Post [request] and return the raw response body.
     */
    suspend fun execute(request: InnerTubeRequest): String = execute(request) { it }

    /**
     * Post [request] and map the raw response body with [parse] — INSIDE the
     * shared retry budget.
     *
     * Decoding is deliberately part of the retried block: a truncated or
     * shape-shifted body is a transient server-side condition here, and this is
     * what makes a malformed response surface as the project's
     * `NetworkException.Serialization` rather than a bare
     * `kotlinx.serialization.SerializationException` escaping the backend. It
     * also guarantees parsing failures and transport failures share ONE budget
     * instead of each having their own.
     *
     * Transport, HTTP-status and cancellation behaviour come from the shared
     * network layer: failures surface as `NetworkException`, 5xx is retried,
     * 4xx and cancellation are not.
     */
    suspend fun <T> execute(request: InnerTubeRequest, parse: (String) -> T): T =
        NaudioHttpClient.requestWithRetry {
            val response = httpClient.post(baseUrl + request.path) {
                contentType(ContentType.Application.Json)
                setBody(TextContent(request.body(), ContentType.Application.Json))
            }
            parse(response.bodyAsText())
        }
}
