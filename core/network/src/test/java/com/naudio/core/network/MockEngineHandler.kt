package com.naudio.core.network

import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/**
 * Shared helpers for deterministic [MockEngine]-based tests. No real network,
 * no real sleeps: retry delays are injected, responses are canned.
 */

/** Handler that always returns the same canned response. */
internal fun constantHandler(
    status: HttpStatusCode,
    body: String,
): MockRequestHandler = { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }

/** Handler that returns canned responses in order; the last one repeats. */
internal fun sequencedHandler(
    vararg statusesAndBodies: Pair<HttpStatusCode, String>,
): MockRequestHandler {
    val responses = statusesAndBodies.toList()
    val queue = ArrayDeque(responses)
    return {
        val (status, body) = if (queue.isEmpty()) statusesAndBodies.last() else queue.removeFirst()
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

/** Handler that always throws, simulating an engine/transport failure. */
internal fun failingHandler(error: Throwable): MockRequestHandler = { throw error }
