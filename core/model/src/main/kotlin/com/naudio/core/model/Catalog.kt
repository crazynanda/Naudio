package com.naudio.core.model

/**
 * Catalog detail models (M21).
 *
 * These are provider-agnostic by construction: they carry a [providerId] string
 * like [Track] does, and nothing else. No endpoint, browse-id namespace, renderer
 * name or transport concept appears here — [providerId] + [id] is the only
 * identity a caller needs to reopen an entity, which is exactly what
 * [com.naudio.data.repository.LibraryRepository] routes on.
 *
 * Distinct from the pre-existing [Artist] and [Playlist] in this package, which
 * model Naudio's own on-device entities (a numeric playlist id, a user-curated
 * local artist reference). A remote catalog artist is a browsable entity with a
 * description, artwork, top tracks and releases, and folding the two together
 * would corrupt both.
 *
 * Every field that a backend may legitimately not supply is nullable or empty,
 * never a placeholder string, so "absent" stays distinguishable from "empty".
 */

/**
 * One artist page: identity, presentation metadata and its catalog content.
 *
 * [id] is the provider's own catalog identifier for the artist (for a browse-id
 * based backend, the channel/browse id). It is NOT a [Track.id] and is only
 * meaningful together with [providerId].
 */
data class ArtistDetail(
    val id: String,
    val providerId: String,
    val name: String,
    /** Artist bio / strapline, when the provider exposes one. */
    val description: String? = null,
    val artworkUrl: String? = null,
    /** The artist's songs, in the provider's own order. */
    val tracks: List<Track> = emptyList(),
    /** The artist's releases. Empty when the provider reports no discography. */
    val albums: List<AlbumSummary> = emptyList(),
) {
    /** True when the page carries neither songs nor releases. */
    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty()
}

/**
 * An album as it appears inside a list (an artist's discography row) — identity
 * and presentation only. The ordered track list belongs to [AlbumDetail], which
 * is fetched when the user opens the album, so browsing an artist with a large
 * discography does not pull every release's track list up front.
 */
data class AlbumSummary(
    val id: String,
    val providerId: String,
    val title: String,
    val artist: String? = null,
    val artworkUrl: String? = null,
    /** Release year, when the provider reports one. Kept as text because the
     *  provider's own formatting (e.g. "1989") is what the UI should show. */
    val year: String? = null,
)

/**
 * One album page: release metadata plus the album's tracks **in release order**.
 *
 * Track order is part of the contract, not an implementation detail: the album
 * screen, its Play action and the queue built from it all use this list exactly
 * as given, so a provider that returns tracks shuffled must be fixed upstream
 * rather than silently reordered here.
 */
data class AlbumDetail(
    val id: String,
    val providerId: String,
    val title: String,
    val artist: String? = null,
    val artworkUrl: String? = null,
    val year: String? = null,
    /** The album's tracks in release order. */
    val tracks: List<Track> = emptyList(),
) {
    /** True when the release has no tracks the provider could report. */
    val isEmpty: Boolean get() = tracks.isEmpty()
}