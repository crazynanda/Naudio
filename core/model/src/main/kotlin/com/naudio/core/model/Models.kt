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
