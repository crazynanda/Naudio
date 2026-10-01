package com.naudio.provider.local

import android.content.ContentUris
import android.net.Uri
import com.naudio.core.model.Track

/** Column values read from one MediaStore.Audio.Media row. */
internal data class MediaStoreTrackRow(
    val id: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    /**
     * Album-art content URI for this row, pre-resolved by the provider from
     * MediaStore's ALBUM_ID (null when the row has no usable album reference).
     */
    val artworkUrl: String? = null,
)

/**
 * Maps a MediaStore row onto the provider-neutral domain model.
 * Unlabelled/untitled media falls back to "Unknown …" placeholders — the same
 * convention Android itself uses — rather than inventing metadata. The mapper
 * is pure (no Android types) so it is unit-testable without Robolectric.
 *
 * M12: the row's artwork reference is passed in pre-resolved by the provider
 * (it needs Android Uri APIs), so the mapper itself stays Android-free.
 */
internal fun MediaStoreTrackRow.toTrack(): Track = Track(
    id = id.toString(),
    providerId = LocalProviderIds.LOCAL,
    title = title?.takeIf { it.isNotBlank() } ?: "Unknown title",
    artist = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist",
    album = album?.takeIf { it.isNotBlank() },
    artworkUrl = artworkUrl,
    durationMs = if (durationMs > 0) durationMs else 0L,
)

/** Content URI for a track's playable audio stream via MediaStore. */
internal fun trackContentUri(id: Long): String = "content://media/external/audio/media/$id"

/**
 * M12: content URI for an album's artwork via MediaStore's album-art
 * provider, built with the platform Uri/ContentUris APIs — never manual
 * string concatenation. Callers pass null through for rows without a
 * usable ALBUM_ID.
 */
internal fun albumArtUri(albumId: Long): String =
    ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
        .toString()
