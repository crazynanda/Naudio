package com.naudio.provider.itunes

import kotlinx.serialization.Serializable

/**
 * Root of an iTunes Search API response (/search and /lookup share the shape).
 * Internal to this module; never leaks into domain or other layers.
 */
@Serializable
internal data class ItunesResponseDto(
    val resultCount: Int = 0,
    val results: List<ItunesTrackDto> = emptyList(),
)

/**
 * One catalog entry. Only the fields naudio consumes are declared; unknown
 * fields in the payload are ignored by the shared [com.naudio.core.network.NaudioHttpClient.json]
 * configuration.
 */
@Serializable
internal data class ItunesTrackDto(
    val trackId: Long,
    val trackName: String,
    val artistName: String,
    val collectionName: String? = null,
    val artworkUrl100: String? = null,
    val trackTimeMillis: Long = 0L,
)
