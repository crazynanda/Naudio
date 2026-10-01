package com.naudio.core.model

/** A playable or referenceable track in the library. */
data class Track(
    val id: String,
    val providerId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
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
