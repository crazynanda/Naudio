package com.naudio.data.repository

import com.naudio.core.database.dao.PlaylistDao
import com.naudio.core.database.dao.TrackDao
import com.naudio.core.database.entity.PlaylistEntity
import com.naudio.core.model.Playlist
import com.naudio.core.model.Track
import com.naudio.data.mapper.PlaylistMapper
import com.naudio.data.mapper.TrackMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single entry point for user-playlist persistence (M13). Keeps Room types
 * out of the UI and domain layers — the domain sees [Playlist] and [Track]
 * values; the DAOs see entities through the mappers.
 *
 * Track identity is composite: (providerId, trackId). A playlist's rows are
 * always returned ordered by `position ASC` (enforced by the DAO queries).
 *
 * The persistence rule for adding a track: the [TrackEntity] is upserted and
 * the membership row inserted inside one database transaction (see
 * [TransactionRunner]) — either both land or neither does.
 */
class PlaylistRepository(
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val transactions: TransactionRunner,
) {

    /** Emits every playlist (newest first) with its track count. */
    fun observePlaylists(): Flow<List<Playlist>> =
        playlistDao.observePlaylistsWithCount().map { rows ->
            rows.map(PlaylistMapper::toDomain)
        }

    /** One playlist (with count), or null when the id is unknown. */
    suspend fun getPlaylist(id: Long): Playlist? =
        playlistDao.playlistWithCount(id)?.let(PlaylistMapper::toDomain)

    /** Reactive variant of [getPlaylist]: emits again on rename/count changes. */
    fun observePlaylist(id: Long): Flow<Playlist?> =
        playlistDao.observePlaylistWithCount(id).map { row -> row?.let(PlaylistMapper::toDomain) }

    /** Creates an empty playlist named [name]; returns its generated id. */
    suspend fun createPlaylist(name: String): Long =
        playlistDao.insertPlaylist(
            PlaylistEntity(
                name = name.trim(),
                createdAt = System.currentTimeMillis(),
            ),
        )

    /** Renames a playlist in place (createdAt and id unchanged). */
    suspend fun renamePlaylist(id: Long, name: String) {
        playlistDao.updatePlaylist(
            PlaylistEntity(
                id = id,
                name = name.trim(),
                // The @Update writes the whole row; createdAt is re-read so the
                // rename can never clobber the original creation time.
                createdAt = playlistDao.playlistWithCount(id)?.playlist?.createdAt
                    ?: System.currentTimeMillis(),
            ),
        )
    }

    /** Deletes a playlist; its membership rows go with it (see the DAO). */
    suspend fun deletePlaylist(id: Long) {
        playlistDao.deletePlaylist(id)
    }

    /**
     * Adds [track] to the end of a playlist:
     *
     * 1. upsert the full track row (refreshing metadata without clobbering
     *    favorite state — same convention as the queue mirroring), then
     * 2. insert the membership row at the next position,
     *
     * both inside a single transaction. Duplicate inserts are absorbed by the
     * DAO (IGNORE) and simply move the track to the end. The underlying track
     * is never deleted by playlist operations.
     */
    suspend fun addTrackToPlaylist(playlistId: Long, track: Track) {
        transactions.inTransaction {
            val existing = trackDao.byIds(listOf(track.providerId), listOf(track.id))
                .firstOrNull { it.providerId == track.providerId && it.id == track.id }
            trackDao.upsertTrack(
                if (existing != null) {
                    existing.copy(
                        title = track.title,
                        artist = track.artist,
                        durationMs = track.durationMs,
                        album = track.album,
                        artworkUrl = track.artworkUrl,
                    )
                } else {
                    TrackMapper.toEntity(track)
                },
            )
            playlistDao.appendTrack(
                playlistId = playlistId,
                providerId = track.providerId,
                trackId = track.id,
            )
        }
    }

    /**
     * Removes one track occurrence from a playlist by composite identity.
     * Only the membership row is deleted — the [TrackEntity] (and its
     * favorite state) always survives.
     */
    suspend fun removeTrackFromPlaylist(playlistId: Long, providerId: String, trackId: String) {
        playlistDao.deleteTrackRef(playlistId, providerId, trackId)
    }

    /** Emits a playlist's tracks, ordered by position ASC, reactively. */
    fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> =
        playlistDao.observeTracks(playlistId).map { entities ->
            entities.map(TrackMapper::toDomain)
        }
}
