package com.naudio.provider.itunes

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.core.network.NaudioHttpClient
import com.naudio.provider.api.PlaybackProvider
import com.naudio.provider.api.ProviderId
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.decodeFromString

/**
 * Playback capability for the iTunes catalog. Resolves a track to the
 * iTunes-served 30-second preview stream ([AudioSource.Remote]) via a
 * provider-owned /lookup request keyed by [Track.id] — the preview URL never
 * travels through the neutral [Track] model.
 *
 * Registered under the same id as [ItunesMetadataProvider] so routing by
 * [Track.providerId] reaches it. No URL is ever derived or fabricated: only
 * the API-served previewUrl is returned, and null when it is absent or the
 * catalog has no such track. Transport failures surface as
 * [com.naudio.core.network.NetworkException] from the shared network layer.
 */
class ItunesPlaybackProvider(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : PlaybackProvider {

    override val id: ProviderId = ProviderId(PROVIDER_ID)

    override suspend fun resolve(track: Track): AudioSource? {
        // Routing is exact: only tracks this provider produced are resolved.
        if (track.providerId != PROVIDER_ID) return null
        if (track.id.isBlank()) return null
        val response = fetch("lookup") { parameter("id", track.id) }
        // iTunes reports a missing id as a 200 with resultCount 0, not a 404.
        val previewUrl = response.results.firstOrNull()?.previewUrl ?: return null
        return AudioSource.Remote(previewUrl)
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
        /** Matches [ItunesMetadataProvider.PROVIDER_ID] so registry routing is exact. */
        const val PROVIDER_ID = ItunesMetadataProvider.PROVIDER_ID

        /** iTunes Search API root. */
        const val DEFAULT_BASE_URL = ItunesMetadataProvider.DEFAULT_BASE_URL
    }
}
