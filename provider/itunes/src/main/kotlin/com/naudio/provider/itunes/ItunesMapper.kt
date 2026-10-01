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
    artworkUrl = artworkUrl100.upgradedArtworkUrl(),
    durationMs = trackTimeMillis,
)

/**
 * M12: iTunes serves artwork at `.../{w}x{h}bb.jpg` sizes; request the
 * 600x600 variant for crisper player artwork. The rewrite is best-effort:
 * a URL without the known `100x100bb.jpg` pattern is preserved verbatim and
 * null stays null, so artwork availability never depends on exact string
 * formatting.
 */
internal fun String?.upgradedArtworkUrl(): String? =
    this?.replace("100x100bb.jpg", "600x600bb.jpg", ignoreCase = true)
