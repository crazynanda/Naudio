package com.naudio.provider.innertube

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

/**
 * Shared helpers for the InnerTube tests. Every test runs on a canned
 * [MockEngine]: no test in this module contacts YouTube.
 */
internal object Fixtures {

    /** Load a JSON fixture from `src/test/resources/innertube`. */
    fun load(name: String): String =
        Fixtures::class.java.getResourceAsStream("/innertube/$name")
            ?.readBytes()
            ?.decodeToString()
            ?: error("missing fixture: innertube/$name")

    /** A handler returning the same canned body for every request. */
    fun json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): MockRequestHandler = { respond(body, status, headersOf("Content-Type", "application/json")) }

    /** A handler returning canned bodies in order; the last one repeats. */
    fun sequenced(vararg bodies: String): MockRequestHandler {
        val queue = ArrayDeque(bodies.toList())
        return {
            val body = if (queue.isEmpty()) bodies.last() else queue.removeFirst()
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
    }

    /** A handler that always throws, simulating an engine/transport failure. */
    fun failing(error: Throwable): MockRequestHandler = { throw error }

    /** A production-shaped client on the given engine. */
    fun client(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) { expectSuccess = true }

    /** The JSON request body sent with [request]. */
    fun bodyOf(request: HttpRequestData): String =
        (request.body as? TextContent)?.text ?: error("expected a TextContent body, got ${request.body::class}")
}