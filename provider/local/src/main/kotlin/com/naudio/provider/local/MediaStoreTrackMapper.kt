package com.naudio.provider.local

import com.naudio.core.model.Track

/** Column values read from one MediaStore.Audio.Media row. */
internal data class MediaStoreTrackRow(
    val id: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
)

/**
 * Maps a MediaStore row onto the provider-neutral domain model.
 * Unlabelled/untitled media falls back to "Unknown …" placeholders — the same
 * convention Android itself uses — rather than inventing metadata. The mapper
 * is pure (no Android types) so it is unit-testable without Robolectric.
 */
internal fun MediaStoreTrackRow.toTrack(): Track = Track(
    id = id.toString(),
    providerId = LocalProviderIds.LOCAL,
    title = title?.takeIf { it.isNotBlank() } ?: "Unknown title",
    artist = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist",
    album = album?.takeIf { it.isNotBlank() },
    durationMs = if (durationMs > 0) durationMs else 0L,
)

/** Content URI for a track's playable audio stream via MediaStore. */
internal fun trackContentUri(id: Long): String = "content://media/external/audio/media/$id"
