package com.naudio.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Room entity for a track. Identity is provider-aware: (provider_id, id) is the
 * composite primary key, so a track from one provider coexists with a track of
 * the same id from another provider.
 *
 * Mapping to the domain [com.naudio.core.model.Track] is handled by
 * [com.naudio.data.mapper.TrackMapper], which lives in :data. Room types stay
 * inside :core:database and never leak into the domain or UI layers.
 */
@Entity(
    tableName = "tracks",
    primaryKeys = ["provider_id", "id"],
)
data class TrackEntity(
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "provider_id")
    val providerId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist")
    val artist: String,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,

    @ColumnInfo(name = "saved_at")
    val savedAt: Long? = null,
)
