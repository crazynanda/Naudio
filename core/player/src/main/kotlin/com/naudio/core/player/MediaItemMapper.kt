package com.naudio.core.player

import android.net.Uri
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.naudio.core.model.Track

/**
 * Deterministic MediaItem identity scheme for the Android Auto browse tree
 * (M14). Three layers of IDs:
 *
 *  - folders:      [ROOT_MEDIA_ID], [FAVORITES_MEDIA_ID], [PLAYLISTS_MEDIA_ID],
 *                  [HISTORY_MEDIA_ID]
 *  - playlists:    `playlist:<id>`
 *  - tracks:       `track:` + Base64Url( length-prefixed (providerId, trackId[, playlistId]) )
 *
 * Track identity MUST preserve the composite `(providerId, trackId)` pair.
 * Neither component is ever assumed to be separator-free: the payload uses
 * per-field length prefixes (`"<len>:<chars>"`) so any byte sequence —
 * including `:`, `|`, whitespace, quotes and unicode — round-trips exactly,
 * then Base64-URL encodes the payload so the resulting media id contains no
 * `:`/`|`/`/` at all. Identity invariant (no collection context):
 *
 *     decode(trackIdOf(p, t)) == MediaId.Track(p, t, TrackContext.NONE, null)
 *
 * Malformed ids are rejected by returning `null` from [decode] — never by
 * throwing into the service callback thread.
 */
@UnstableApi
object MediaItemMapper {

    /** Root of the browse tree. */
    const val ROOT_MEDIA_ID = "root"

    /** Browsable folder holding every favorite. */
    const val FAVORITES_MEDIA_ID = "favorites-folder"

    /** Browsable folder holding every user playlist. */
    const val PLAYLISTS_MEDIA_ID = "playlists-folder"

    /**
     * M17: browsable folder holding the recently played history events, newest
     * first. Rendered from the history rows' metadata snapshots.
     */
    const val HISTORY_MEDIA_ID = "history-folder"

    private const val PLAYLIST_PREFIX = "playlist:"
    private const val TRACK_PREFIX = "track:"
    private const val PLAYLIST_CONTEXT_PREFIX = "pl"
    private const val TRACK_IN_FAVORITES = "fav"
    private const val TRACK_IN_HISTORY = "hist"
    private const val TRACK_NO_CONTEXT = "none"

    /**
     * Sentinel playlist id meaning "this track was served from Favorites".
     * Passed to [trackIdOf] instead of a real playlist id; decodes back to
     * [TrackContext.FAVORITES].
     */
    const val FAVORITES_CONTEXT = -1L

    /**
     * M17: sentinel "playlist id" meaning "this track was served from the
     * Recently Played folder". Passed to [trackIdOf] instead of a real playlist
     * id; decodes back to [TrackContext.HISTORY].
     */
    const val HISTORY_CONTEXT = -2L

    /** Upper bound on accepted ids; guards against pathological inputs. */
    private const val MAX_MEDIA_ID_LENGTH = 4096

    private val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    /** Where a track node was served from — decides the enqueue context. */
    enum class TrackContext {
        /** Standalone track (no collection context). */
        NONE,

        /** Served from the Favorites folder. */
        FAVORITES,

        /** Served from the playlist with the given id. */
        PLAYLIST,

        /**
         * M17: served from the Recently Played folder, so playing it enqueues
         * the history list exactly the way Favorites enqueues the favorites.
         */
        HISTORY,
    }

    /** Decoded structure of a media id. */
    sealed interface MediaId {
        data object Root : MediaId
        data object Favorites : MediaId
        data object Playlists : MediaId

        /** The Recently Played folder (M17). */
        data object History : MediaId

        /** A user playlist node. */
        data class Playlist(val playlistId: Long) : MediaId

        /**
         * A track, keeping the composite identity. [context] records which
         * collection the node was served from so playback can enqueue that
         * whole collection (the same way the mobile UI plays from it).
         */
        data class Track(
            val providerId: String,
            val trackId: String,
            val context: TrackContext = TrackContext.NONE,
            val playlistId: Long? = null,
        ) : MediaId
    }

    /** Media id for a playlist node, or null for a negative id. */
    fun playlistIdOf(playlistId: Long): String? =
        if (playlistId >= 0L) PLAYLIST_PREFIX + playlistId else null

    /**
     * Media id for a track. [playlistId] marks the track as served from that
     * playlist (context); favorites context is marked with [TRACK_IN_FAVORITES].
     * Returns null for an empty provider/track id or a negative playlist id —
     * callers treat null as "not representable".
     */
    fun trackIdOf(providerId: String, trackId: String, playlistId: Long? = null): String? {
        if (providerId.isEmpty() || trackId.isEmpty()) return null
        if (playlistId != null && playlistId < 0L && playlistId != FAVORITES_CONTEXT && playlistId != HISTORY_CONTEXT) {
            return null
        }
        val contextField = when (playlistId) {
            null -> TRACK_NO_CONTEXT
            FAVORITES_CONTEXT -> TRACK_IN_FAVORITES
            HISTORY_CONTEXT -> TRACK_IN_HISTORY
            else -> PLAYLIST_CONTEXT_PREFIX + playlistId
        }
        val payload = buildString {
            append(providerId.length).append(':').append(providerId)
            append('|')
            append(trackId.length).append(':').append(trackId)
            append('|')
            append(contextField)
        }
        val mediaId = TRACK_PREFIX + Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), BASE64_FLAGS)
        // Symmetric with the decode-side bound: an id this source could never
        // decode is never produced in the first place.
        return if (mediaId.length <= MAX_MEDIA_ID_LENGTH) mediaId else null
    }

    /** Decodes a media id; null when malformed. Never throws. */
    fun decode(mediaId: String?): MediaId? {
        if (mediaId.isNullOrEmpty() || mediaId.length > MAX_MEDIA_ID_LENGTH) return null
        return when (mediaId) {
            ROOT_MEDIA_ID -> MediaId.Root
            FAVORITES_MEDIA_ID -> MediaId.Favorites
            PLAYLISTS_MEDIA_ID -> MediaId.Playlists
            HISTORY_MEDIA_ID -> MediaId.History
            else -> decodePlaylistOrTrack(mediaId)
        }
    }

    private fun decodePlaylistOrTrack(mediaId: String): MediaId? {
        if (mediaId.startsWith(PLAYLIST_PREFIX)) {
            val id = mediaId.removePrefix(PLAYLIST_PREFIX).toLongOrNull() ?: return null
            return if (id >= 0L) MediaId.Playlist(id) else null
        }
        if (!mediaId.startsWith(TRACK_PREFIX)) return null
        val encoded = mediaId.removePrefix(TRACK_PREFIX)
        val payload = try {
            String(Base64.decode(encoded, BASE64_FLAGS), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // Sequential parse: length prefixes are honored BEFORE any delimiter
        // is looked at, so field values may themselves contain '|' or ':'.
        var index = 0
        fun readField(): String? {
            val colon = payload.indexOf(':', index)
            if (colon < 0) return null
            val length = payload.substring(index, colon).toIntOrNull() ?: return null
            if (length < 0 || colon + 1 + length > payload.length) return null
            val value = payload.substring(colon + 1, colon + 1 + length)
            index = colon + 1 + length
            return value
        }
        val providerId = readField() ?: return null
        if (index >= payload.length || payload[index] != '|') return null
        index++
        val trackId = readField() ?: return null
        if (providerId.isEmpty() || trackId.isEmpty()) return null
        if (index >= payload.length || payload[index] != '|') return null
        val contextField = payload.substring(index + 1)
        var context = TrackContext.NONE
        var playlistId: Long? = null
        when {
            contextField == TRACK_NO_CONTEXT -> Unit
            contextField == TRACK_IN_FAVORITES -> {
                context = TrackContext.FAVORITES
                playlistId = FAVORITES_CONTEXT
            }
            contextField == TRACK_IN_HISTORY -> {
                context = TrackContext.HISTORY
                playlistId = HISTORY_CONTEXT
            }
            contextField.startsWith(PLAYLIST_CONTEXT_PREFIX) -> {
                context = TrackContext.PLAYLIST
                playlistId = contextField.removePrefix(PLAYLIST_CONTEXT_PREFIX).toLongOrNull() ?: return null
                if (playlistId < 0L) return null
            }
            else -> return null
        }
        return MediaId.Track(providerId, trackId, context, playlistId)
    }

    // ------------------------------------------------------------------
    // MediaItem / MediaMetadata builders
    // ------------------------------------------------------------------

    /** Browsable, non-playable folder node. */
    fun folderItem(mediaId: String, title: String, mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .build(),
        )
        .build()

    /** Browsable, non-playable playlist node. */
    fun playlistItem(playlistId: Long, name: String, trackCount: Int): MediaItem = MediaItem.Builder()
        .setMediaId(playlistIdOf(playlistId) ?: "")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(if (trackCount == 1) "1 track" else "$trackCount tracks")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                .build(),
        )
        .build()

    /** Playable, non-browsable track node (no URI: resolved at playback time). */
    fun trackMediaItem(track: Track, mediaId: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId ?: trackIdOf(track.providerId, track.id) ?: "")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setArtworkUri(track.artworkUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse))
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    /** Track metadata: title/artist/album/artwork only — never URLs or tokens. */
    fun trackMetadata(track: Track): MediaMetadata = MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
        .setAlbumTitle(track.album)
        .setArtworkUri(track.artworkUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse))
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .build()
}
