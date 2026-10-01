package com.naudio.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a user playlist (M13). The user owns the name and creation
 * time; the playlist's tracks live in [PlaylistTrackCrossRef], ordered by
 * `position ASC`.
 *
 * Domain mapping lives in [com.naudio.data.mapper.PlaylistMapper] (:data) —
 * Room types never leak past the repository layer.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
