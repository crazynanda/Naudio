package com.naudio.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row (id = 0) holder for the persistent queue's playback position.
 * Kept separate from the item rows so index updates never touch the queue
 * itself. `current_index` is null when nothing was ever queued/played.
 */
@Entity(tableName = "queue_state")
data class QueueStateEntity(
    @PrimaryKey
    val id: Int = 0,

    /** Position in the queue the user last played; null = no position. */
    @ColumnInfo(name = "current_index")
    val currentIndex: Int? = null,
)
