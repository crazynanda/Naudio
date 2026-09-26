package com.naudio.provider.itunes

import com.naudio.core.model.Track

/**
 * Maps an iTunes catalog entry onto the provider-neutral domain model.
 * Absent optional fields stay null/0 — no metadata is invented.
 */
internal fun ItunesTrackDto.toDomain(): Track = Track(
    id = trackId.toString(),
    providerId = ItunesMetadataProvider.PROVIDER_ID,
    title = trackName,
    artist = artistName,
    album = collectionName,
    artworkUrl = artworkUrl100,
    durationMs = trackTimeMillis,
)
