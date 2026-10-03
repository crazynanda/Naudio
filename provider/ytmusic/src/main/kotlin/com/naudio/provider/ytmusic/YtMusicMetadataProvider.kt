package com.naudio.provider.ytmusic

import com.naudio.core.model.Track
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.Page
import com.naudio.provider.api.PageToken
import com.naudio.provider.api.ProviderId
import com.naudio.provider.innertube.api.YtMusicBackend

/**
 * YouTube Music catalog metadata provider (anonymous, unauthenticated).
 *
 * Implements [MetadataProvider] on top of [YtMusicBackend] and does nothing
 * else: it owns no HTTP client, builds no requests, and parses no responses.
 * Every YT Music network call lives behind the backend abstraction
 * (`com.naudio.provider.innertube`), so swapping that implementation is
 * invisible from here — and no InnerTube type is visible from here at all.
 *
 * What this class still owns, and always did:
 *  - [ProviderId] registration and the display name,
 *  - the translation between the project's [PageToken] discipline and the
 *    backend's opaque continuation string,
 *  - the blank-query short circuit.
 *
 * Pagination uses opaque server continuation tokens exclusively:
 * - null token            -> first page (songs-filtered search)
 * - [PageToken.Opaque]    -> continuation request, verbatim token
 * - [PageToken.Offset]    -> caller error, fails deterministically (a numeric
 *   offset can never be a valid InnerTube continuation)
 *
 * Failures thrown by the backend surface unchanged; cancellation propagates
 * untouched. Neither is caught or converted here.
 */
class YtMusicMetadataProvider(
    private val backend: YtMusicBackend,
) : MetadataProvider {

    override val id: ProviderId = ProviderId(YtMusicProviderIds.YTMUSIC)

    override val displayName: String = YtMusicProviderIds.DISPLAY_NAME

    override suspend fun searchTracks(
        query: String,
        token: PageToken?,
        limit: Int,
    ): Page<Track> {
        if (query.isBlank()) return Page(emptyList(), nextToken = null)
        // Continuation pages are driven by the server token; `limit` is part of
        // the backend's client contract, not adjustable per request.
        val continuation = when (token) {
            null -> null
            is PageToken.Opaque -> token.value
            is PageToken.Offset ->
                throw IllegalArgumentException(TOKEN_TYPE_ERROR)
        }
        return backend.search(query, continuation)
    }

    /**
     * Single-track metadata, resolved through the backend's song operation.
     * Returns null for an id the backend does not know; that is a normal
     * "not found", not a failure.
     */
    override suspend fun lookupTrack(id: String): Track? = backend.song(id)

    companion object {
        /** Stable provider identifier used by the registry. */
        const val PROVIDER_ID = YtMusicProviderIds.YTMUSIC

        /** Human-readable provider name shown in the UI. */
        const val DISPLAY_NAME = YtMusicProviderIds.DISPLAY_NAME

        /** Error message for an incompatible (non-opaque) page token. */
        const val TOKEN_TYPE_ERROR = "YouTube Music provider requires PageToken.Opaque"
    }
}