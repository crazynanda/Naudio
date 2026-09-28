package com.naudio.provider.ytmusic

import com.naudio.core.model.Track
import com.naudio.core.network.NaudioHttpClient
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.Page
import com.naudio.provider.api.PageToken
import com.naudio.provider.api.ProviderId
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.contentType

/**
 * YouTube Music catalog metadata provider (anonymous, unauthenticated).
 * Implements [MetadataProvider] ONLY — there is deliberately no YTM playback
 * provider: this module never extracts, resolves, or inspects audio streams,
 * streamingData, signatureCipher, or playback URLs.
 *
 * Networking uses the shared application-lifetime [HttpClient] via the
 * network layer's bounded retry ([NaudioHttpClient.requestWithRetry]); no
 * second client and no second retry system. Requests carry no cookies,
 * sessions, OAuth tokens, or authentication headers — only the public web
 * client's context, the same anonymous identity the music.youtube.com web
 * player itself sends.
 *
 * Pagination uses opaque server continuation tokens exclusively:
 * - null token            -> first page (songs-filtered search)
 * - PageToken.Opaque      -> continuation request, verbatim token
 * - PageToken.Offset      -> caller error, fails deterministically (a numeric
 *   offset can never be a valid InnerTube continuation)
 *
 * Failures throw [NetworkException]; malformed/changed JSON surfaces as
 * [NetworkException.Serialization] (a controlled failure, never an empty
 * result). Cancellation propagates untouched.
 */
class YtMusicMetadataProvider(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : MetadataProvider {

    override val id: ProviderId = ProviderId(YtMusicProviderIds.YTMUSIC)

    override val displayName: String = YtMusicProviderIds.DISPLAY_NAME

    override suspend fun searchTracks(
        query: String,
        token: PageToken?,
        limit: Int,
    ): Page<Track> {
        if (query.isBlank()) return Page(emptyList(), nextToken = null)
        // Continuation pages are driven by the server token; `limit` is part
        // of the web client contract, not adjustable per request.
        val continuation = when (token) {
            null -> null
            is PageToken.Opaque -> token.value
            is PageToken.Offset ->
                throw IllegalArgumentException(TOKEN_TYPE_ERROR)
        }
        val parsed = fetch(YtMusicResponseParser.buildSearchBody(query, continuation))
        val next = parsed.continuation?.let { PageToken.Opaque(it) }
        return Page(parsed.tracks, next)
    }

    /**
     * Single-track metadata is not wired in this milestone: the anonymous
     * search endpoint cannot query one song id and no detail endpoint is
     * implemented yet, so unknown ids report null rather than guessed
     * metadata. Search remains the only metadata surface of this provider.
     */
    override suspend fun lookupTrack(id: String): Track? = null

    private suspend fun fetch(body: String): YtMusicResponseParser.ParsedSearchPage =
        NaudioHttpClient.requestWithRetry {
            // Raw JSON body via TextContent: ContentNegotiation on the shared
            // client would otherwise re-encode a plain String body.
            val response = client.post("$baseUrl$SEARCH_PATH") {
                contentType(ContentType.Application.Json)
                setBody(TextContent(body, ContentType.Application.Json))
            }
            // Decoded explicitly through the InnerTube parser: the response is
            // a deeply nested JsonObject graph with no useful DTO mapping.
            YtMusicResponseParser.parseSearchResponse(response.bodyAsText())
        }

    companion object {
        /** Stable provider identifier used by the registry. */
        const val PROVIDER_ID = YtMusicProviderIds.YTMUSIC

        /** Human-readable provider name shown in the UI. */
        const val DISPLAY_NAME = YtMusicProviderIds.DISPLAY_NAME

        /** InnerTube API root used by the YouTube Music web client. */
        const val DEFAULT_BASE_URL = "https://music.youtube.com"

        /** Search endpoint path under [DEFAULT_BASE_URL]. */
        const val SEARCH_PATH = "/youtubei/v1/search"

        /** Error message for an incompatible (non-opaque) page token. */
        const val TOKEN_TYPE_ERROR = "YouTube Music provider requires PageToken.Opaque"
    }
}
