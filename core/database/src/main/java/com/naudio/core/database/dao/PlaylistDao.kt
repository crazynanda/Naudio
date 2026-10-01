package com.naudio.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.naudio.core.database.entity.PlaylistEntity
import com.naudio.core.database.entity.PlaylistTrackCrossRef
import com.naudio.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room relation joining a playlist with its track count. `track_count` is
 * supplied by the DAO's GROUP BY query; the playlist columns come from
 * `playlists`. Keeping it here (next to [PlaylistDao]) mirrors how the
 * entity/DAO pairing keeps Room types inside :core:database.
 */
data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    @ColumnInfo(name = "track_count") val trackCount: Int,
)

/**
 * DAO for user playlists (M13). No playback logic here — playback reuses the
 * existing queue architecture; this DAO only stores and reads ordered
 * membership.
 */
@Dao
interface PlaylistDao {

    /**
     * Reactive observation of all playlists (newest first) with their track
     * counts. LEFT JOIN so empty playlists still appear with count 0.
     */
    @Query(
        """
        SELECT playlists.*, COUNT(playlist_tracks.track_id) AS track_count
        FROM playlists
        LEFT JOIN playlist_tracks ON playlist_tracks.playlist_id = playlists.id
        GROUP BY playlists.id
        ORDER BY playlists.created_at DESC
        """
    )
    fun observePlaylistsWithCount(): Flow<List<PlaylistWithCount>>

    /** One playlist's count row, or null when the playlist does not exist. */
    @Query(
        """
        SELECT playlists.*, COUNT(playlist_tracks.track_id) AS track_count
        FROM playlists
        LEFT JOIN playlist_tracks ON playlist_tracks.playlist_id = playlists.id
        WHERE playlists.id = :playlistId
        GROUP BY playlists.id
        """
    )
    suspend fun playlistWithCount(playlistId: Long): PlaylistWithCount?

    /** Reactive variant of [playlistWithCount] (detail screen live metadata). */
    @Query(
        """
        SELECT playlists.*, COUNT(playlist_tracks.track_id) AS track_count
        FROM playlists
        LEFT JOIN playlist_tracks ON playlist_tracks.playlist_id = playlists.id
        WHERE playlists.id = :playlistId
        GROUP BY playlists.id
        """
    )
    fun observePlaylistWithCount(playlistId: Long): Flow<PlaylistWithCount?>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    /**
     * Delete a playlist by id. The playlist_tracks FK has no ON DELETE action,
     * so membership rows are removed first — SQLite enforces that no orphaned
     * cross-ref survives its playlist.
     */
    @Transaction
    suspend fun deletePlaylist(playlistId: Long) {
        clearTracks(playlistId)
        deletePlaylistRow(playlistId)
    }

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistRow(playlistId: Long)

    /** Insert one membership row. IGNORE = silently keeps the playlist duplicate-free. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackRef(ref: PlaylistTrackCrossRef): Long

    /** Remove one membership row by composite track identity. */
    @Query(
        """
        DELETE FROM playlist_tracks
        WHERE playlist_id = :playlistId
          AND provider_id = :providerId
          AND track_id = :trackId
        """
    )
    suspend fun deleteTrackRef(playlistId: Long, providerId: String, trackId: String)

    /** Remove every membership row of a playlist. */
    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun clearTracks(playlistId: Long)

    /** The tracks of one playlist, ordered by position ASC. */
    @Query(
        """
        SELECT tracks.* FROM tracks
        INNER JOIN playlist_tracks
          ON playlist_tracks.provider_id = tracks.provider_id
         AND playlist_tracks.track_id = tracks.id
        WHERE playlist_tracks.playlist_id = :playlistId
        ORDER BY playlist_tracks.position ASC
        """
    )
    fun observeTracks(playlistId: Long): Flow<List<TrackEntity>>

    /**
     * Append a track to the end of a playlist, transactionally. A duplicate
     * add is a no-op returning −1 (every existing position stays untouched —
     * membership order is never reshuffled). A new track is placed at
     * `MAX(position) + 1`, which keeps ordering correct even when earlier
     * removals left position gaps. Returns the row id of the inserted
     * membership (−1 when the track is already a member).
     */
    @Transaction
    suspend fun appendTrack(
        playlistId: Long,
        providerId: String,
        trackId: String,
    ): Long {
        if (countTrack(playlistId, providerId, trackId) > 0) return -1L
        val position = (maxPosition(playlistId) ?: -1) + 1
        return insertTrackRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                providerId = providerId,
                trackId = trackId,
                position = position,
            ),
        )
    }

    /** 1 when the track is already a member of the playlist, else 0. */
    @Query(
        """
        SELECT COUNT(*) FROM playlist_tracks
        WHERE playlist_id = :playlistId
          AND provider_id = :providerId
          AND track_id = :trackId
        """
    )
    suspend fun countTrack(playlistId: Long, providerId: String, trackId: String): Int

    /** Highest position currently used by a playlist (null when empty). */
    @Query("SELECT MAX(position) FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int?
}
