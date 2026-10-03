package com.naudio.provider.innertube.request

import com.naudio.provider.innertube.client.InnerTubeEndpoints
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Every request body this module can send, as a closed set.
 *
 * Requests are values: constructing one performs no I/O and the body is derived
 * by [body]. That keeps "which JSON does this operation send?" a pure, directly
 * unit-testable question, independent of the HTTP client.
 *
 * Internal — request models are InnerTube knowledge and must not be visible
 * outside the backend.
 */
internal sealed interface InnerTubeRequest {

    /** Endpoint path this request is posted to. */
    val path: String

    /** The JSON body to send. */
    fun body(): String

    /**
     * Songs-filtered catalog search. [params] is the web client's audio-only /
     * official-songs filter and is sent on the first page only; a continuation
     * page sends the token instead, exactly as the web client does.
     */
    data class Search(
        val query: String,
        val continuation: String? = null,
    ) : InnerTubeRequest {
        override val path: String = InnerTubeEndpoints.SEARCH

        override fun body(): String = buildJsonObject {
            put("query", query)
            if (continuation == null) {
                put("params", SONGS_FILTER_PARAMS)
            } else {
                put("continuation", continuation)
            }
            put("context", InnerTubeContext.build())
        }.toString()

        companion object {
            /** Songs filter scope: audio-only + official songs (web client encoding). */
            const val SONGS_FILTER_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        }
    }

    /**
     * Browse a channel, album, playlist, or the home feed. [browseId] is the
     * opaque browse identifier the backend handed out; [params] optionally
     * scopes the browse to one tab/shelf.
     */
    data class Browse(
        val browseId: String,
        val params: String? = null,
        val continuation: String? = null,
    ) : InnerTubeRequest {
        override val path: String = InnerTubeEndpoints.BROWSE

        override fun body(): String = buildJsonObject {
            put("browseId", browseId)
            params?.let { put("params", it) }
            continuation?.let { put("continuation", it) }
            put("context", InnerTubeContext.build())
        }.toString()
    }

    /**
     * The watch page for one video: single-song metadata plus the related
     * rail. Same request, two parsers.
     */
    data class Next(val videoId: String) : InnerTubeRequest {
        override val path: String = InnerTubeEndpoints.NEXT

        override fun body(): String = buildJsonObject {
            put("videoId", videoId)
            put("context", InnerTubeContext.build())
        }.toString()
    }

    /**
     * Playback-resolution metadata for one video.
     *
     * The body carries the video id and the shared anonymous context, and
     * nothing else. It deliberately does NOT send a `playbackContext` /
     * `signatureTimestamp` combination, a PO token, or any other field whose
     * purpose would be to unlock a stream this client is not entitled to
     * unauthenticated.
     */
    data class Player(val videoId: String) : InnerTubeRequest {
        override val path: String = InnerTubeEndpoints.PLAYER

        override fun body(): String = buildJsonObject {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", InnerTubeContext.build())
        }.toString()
    }
}
