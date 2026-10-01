package com.naudio.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Ordered membership of a [TrackEntity] in a [PlaylistEntity] (M13).
 *
 * Track identity is composite — (provider_id, track_id) mirrors
 * [TrackEntity]'s composite primary key — so a playlist can reference the
 * same numeric track id from two different providers without collision. The
 * composite foreign keys keep `tracks` as the single source of track
 * identities: adding a cross-ref for a track that was never persisted fails.
 * No `ON DELETE` action cascades into `tracks` — removing a playlist row
 * (or its membership) never deletes the underlying track.
 *
 * `position` defines the playlist order (always read back `position ASC`).
 * The unique index on (playlist_id, provider_id, track_id) enforces the M13
 * duplicate-track prevention: the same track cannot appear twice in one
 * playlist.
 */
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlist_id", "provider_id", "track_id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["provider_id", "id"],
            childColumns = ["provider_id", "track_id"],
        ),
    ],
    indices = [
        Index("playlist_id"),
        Index(value = ["provider_id", "track_id"]),
        // Duplicate prevention: one occurrence per track per playlist.
        Index(value = ["playlist_id", "provider_id", "track_id"], unique = true),
    ],
)
data class PlaylistTrackCrossRef(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,

    @ColumnInfo(name = "provider_id")
    val providerId: String,

    @ColumnInfo(name = "track_id")
    val trackId: String,

    /** Zero-based, dense ordering within the playlist; read back `position ASC`. */
    @ColumnInfo(name = "position")
    val position: Int,
)
