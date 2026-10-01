package com.naudio.provider.local

import android.content.ContentResolver
import android.content.Context
import android.provider.MediaStore
import com.naudio.core.model.Track
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.Page
import com.naudio.provider.api.PageToken
import com.naudio.provider.api.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device catalog metadata provider backed by MediaStore's audio index.
 * Uses only MediaStore's indexed metadata (no MediaMetadataRetriever, no ID3
 * parsing). Permission handling lives in the app layer: if the app lacks
 * media-read permission, MediaStore throws [SecurityException], which this
 * provider rethrows untouched so the UI can show a permission-required state.
 *
 * The provider never requests permissions and never talks to a player.
 *
 * M12: album artwork is resolved from the row's ALBUM_ID into the standard
 * MediaStore album-art content URI (`content://media/external/audio/albumart
 * /{albumId}`) and stored as the track's artworkUrl. Rows without a usable
 * ALBUM_ID simply carry null artwork — never a crash.
 */
class LocalMetadataProvider(
    context: Context,
) : MetadataProvider {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override val id: ProviderId = ProviderId(LocalProviderIds.LOCAL)

    override val displayName: String = LocalProviderIds.DISPLAY_NAME

    private val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.ALBUM_ID,
    )

    override suspend fun searchTracks(
        query: String,
        token: PageToken?,
        limit: Int,
    ): Page<Track> {
        if (query.isBlank()) return Page(emptyList(), nextToken = null)
        // MediaStore pages by native numeric offset only; a continuation token
        // can never be a valid cursor here and fails deterministically.
        val offset = when (token) {
            null -> 0
            is PageToken.Offset -> token.value
            is PageToken.Opaque -> throw IllegalArgumentException(LocalProviderIds.TOKEN_TYPE_ERROR)
        }
        val selection = "${MediaStore.Audio.Media.TITLE} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        return queryPage(selection, selectionArgs, offset, limit)
    }

    override suspend fun lookupTrack(id: String): Track? {
        val idLong = id.toLongOrNull() ?: return null
        val selection = "${MediaStore.Audio.Media._ID} = ?"
        return queryPage(selection, arrayOf(idLong.toString()), offset = 0, limit = 1).items.firstOrNull()
    }

    /**
     * One sorted MediaStore query per page. The requested page is taken as the
     * [offset, offset + limit) slice of the cursor — deterministic across all
     * API levels and provider implementations (some modern MediaProvider builds
     * reject SQL `LIMIT`/`OFFSET` suffixes in the sort order). [Page.nextToken]
     * follows the same short-page-means-last rule as the iTunes provider.
     * Throws [SecurityException] untouched when read permission is missing.
     */
    private suspend fun queryPage(
        selection: String,
        selectionArgs: Array<String>,
        offset: Int,
        limit: Int,
    ): Page<Track> = withContext(Dispatchers.IO) {
        val from = offset.coerceAtLeast(0)
        val items = mutableListOf<Track>()
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            MediaStore.Audio.Media.DEFAULT_SORT_ORDER,
        )?.use { cursor ->
            var skipped = 0
            while (skipped < from && cursor.moveToNext()) skipped++
            while (items.size < limit && cursor.moveToNext()) {
                // Only a positive album id yields an album-art URI; missing or
                // invalid values leave the artwork null (graceful fallback).
                val albumId = cursor.getLong(5).takeIf { it > 0L }
                items += MediaStoreTrackRow(
                    id = cursor.getLong(0),
                    title = cursor.getString(1),
                    artist = cursor.getString(2),
                    album = cursor.getString(3),
                    durationMs = cursor.getLong(4),
                    artworkUrl = albumId?.let { albumArtUri(it) },
                ).toTrack()
            }
        }
        val next = if (items.size < limit) null else offset + items.size
        Page(items, next?.let { PageToken.Offset(it) })
    }
}
