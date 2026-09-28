package com.naudio.provider.api

import com.naudio.core.model.Track

/**
 * Read-only contract for anything that can supply track catalog metadata.
 * Metadata and playback are deliberately separate capabilities: this interface
 * must never depend on a player, and a metadata provider needs no matching
 * playback provider to be registered.
 */
interface MetadataProvider {
    /** Stable identifier of this provider, used by the registry and UI. */
    val id: ProviderId

    /** Human-readable name shown in the UI. */
    val displayName: String

    /**
     * Search the catalog for tracks matching [query], starting at the page
     * identified by [token] (null = first page). Returns one page of
     * [Page.items]; [Page.nextToken] is null when the page is the last one
     * (including the empty case). Failures (network, transport) throw; an
     * empty catalog result is not a failure.
     *
     * Token discipline: a provider consumes only the [PageToken] kind its own
     * backend produces (iTunes/MediaStore consume [PageToken.Offset]; a
     * continuation-based provider consumes [PageToken.Opaque]). An
     * incompatible token is a caller error (stale/mixed cursor) and must fail
     * deterministically — implementations must never silently reinterpret it
     * as a valid cursor for their backend.
     */
    suspend fun searchTracks(
        query: String,
        token: PageToken? = null,
        limit: Int = 50,
    ): Page<Track>

    /** Resolve full metadata for a single track, or null if unknown. */
    suspend fun lookupTrack(id: String): Track?
}
