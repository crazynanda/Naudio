package com.naudio.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Room entity for one entry of the persistent playback queue (M10).
 *
 * The queue is an ordered snapshot of [com.naudio.core.model.Track] values:
 * `order_index` is the primary key and defines playback order. Identity is
 * provider-aware — (provider_id, track_id) mirrors [TrackEntity]'s composite
 * identity, so queue rows never collide across providers. The full metadata
 * snapshot lets the queue be restored at startup without joining `tracks`.
 */
@Entity(
    tableName = "queue_items",
    primaryKeys = ["order_index"],
)
data class QueueEntity(
    /** Playback position of this item; 0-based and dense after a replace. */
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,

    @ColumnInfo(name = "provider_id")
    val providerId: String,

    @ColumnInfo(name = "track_id")
    val trackId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist")
    val artist: String,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
)
