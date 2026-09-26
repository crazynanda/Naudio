package com.naudio.provider.itunes

import com.naudio.core.network.NaudioHttpClient
import com.naudio.core.model.Track
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.Page
import com.naudio.provider.api.ProviderId
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.decodeFromString

/**
 * Online catalog metadata provider backed by the public iTunes Search API
 * (no authentication). Metadata only: this module deliberately implements no
 * playback capability, so no iTunes PlaybackProvider exists.
 *
 * Uses the application-lifetime [HttpClient] injected by the DI container and
 * reuses the network layer's retry/error architecture via
 * [NaudioHttpClient.requestWithRetry]. An empty catalog result is a normal
 * [Page]/null; transport failures throw [com.naudio.core.network.NetworkException].
 */
class ItunesMetadataProvider(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : MetadataProvider {

    override val id: ProviderId = ProviderId(PROVIDER_ID)

    override val displayName: String = DISPLAY_NAME

    override suspend fun searchTracks(
        query: String,
        offset: Int,
        limit: Int,
    ): Page<Track> {
        if (query.isBlank()) return Page(emptyList(), nextOffset = null)
        val response = fetch("search") {
            parameter("term", query)
            parameter("entity", "song")
            parameter("limit", limit)
            parameter("offset", offset)
        }
        val results = response.results
        // Full page -> more results may exist; short page -> last page.
        val nextOffset = if (results.size < limit) null else offset + results.size
        return Page(results.map { it.toDomain() }, nextOffset)
    }

    override suspend fun lookupTrack(id: String): Track? {
        if (id.isBlank()) return null
        val response = fetch("lookup") { parameter("id", id) }
        // iTunes reports a missing id as a 200 with resultCount 0, not a 404.
        return response.results.firstOrNull()?.toDomain()
    }

    private suspend fun fetch(
        path: String,
        params: HttpRequestBuilder.() -> Unit,
    ): ItunesResponseDto = NaudioHttpClient.requestWithRetry {
        val response = client.get("$baseUrl/$path") { params() }
        // Decoded explicitly: iTunes serves "text/javascript", which the
        // ContentNegotiation plugin would not match by content type.
        NaudioHttpClient.json.decodeFromString<ItunesResponseDto>(response.bodyAsText())
    }

    companion object {
        /** Stable provider identifier used by the registry. */
        const val PROVIDER_ID = "itunes"

        /** Human-readable provider name shown in the UI. */
        const val DISPLAY_NAME = "iTunes"

        /** iTunes Search API root. */
        const val DEFAULT_BASE_URL = "https://itunes.apple.com"
    }
}
