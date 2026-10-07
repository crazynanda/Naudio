package com.naudio.provider.api

import com.naudio.core.model.AlbumDetail
import com.naudio.core.model.ArtistDetail
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

    /**
     * Resolve one artist page, or null when this provider has no artist
     * catalog or nothing for [artistId] (M21).
     *
     * Catalog detail is a SEPARATE capability from track search, so it is
     * optional: the default returns null, which every provider that does not
     * browse artists inherits for free. Adding these as default methods is
     * what keeps the interface source-compatible — an existing provider keeps
     * compiling and keeps working unchanged.
     *
     * [artistId] is this provider's own catalog identifier, exactly as carried
     * by [ArtistDetail.id]; callers must not pass a [Track.id] or invent an id
     * from a display name.
     */
    suspend fun getArtist(artistId: String): ArtistDetail? = null

    /**
     * Resolve one album page, or null when this provider has no album catalog
     * or nothing for [albumId] (M21).
     *
     * [AlbumDetail.tracks] is returned in release order and callers rely on that
     * order for both display and queueing.
     */
    suspend fun getAlbum(albumId: String): AlbumDetail? = null
}
