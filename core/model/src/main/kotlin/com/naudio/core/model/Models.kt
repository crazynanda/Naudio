package com.naudio.core.model

/**
 * A playable or referenceable track in the library.
 *
 * [artist] and [album] are DISPLAY strings; [artistId] and [albumId] are the
 * provider's own catalog identity for exactly those two entities, when the
 * provider reported one. They are separate fields on purpose: a display name is
 * never an id, and nothing may substitute one for the other.
 *
 * Both ids are provider-agnostic strings (like [providerId] and [id]) and mean
 * nothing outside the provider that issued them — reopening an artist needs
 * [providerId] + [artistId] together. They are null whenever the provider
 * reports no such entity, which is the normal case for the local library and for
 * a source that lists no catalog links, and "absent" therefore stays
 * distinguishable from "empty".
 *
 * Appended last, with defaults, so existing positional construction and every
 * equality assertion on a track is unaffected.
 */
data class Track(
    val id: String,
    val providerId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    /** Catalog id of [artist], or null when the provider reported none. */
    val artistId: String? = null,
    /** Catalog id of [album], or null when the provider reported none. */
    val albumId: String? = null,
)

/** An artist referenced by tracks. */
data class Artist(
    val id: String,
    val name: String,
)

/**
 * A user playlist (M13). Deliberately minimal: identity, display name and the
 * current track count. The ordered track list is loaded per playlist through
 * the repository, and playlist artwork (if ever) is out of scope for M13.
 */
data class Playlist(
    val id: Long,
    val name: String,
    val trackCount: Int,
)
