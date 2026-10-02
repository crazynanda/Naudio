package com.naudio.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.naudio.core.database.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the playback-history event log (M17). Only the three operations M17
 * needs — insert, observe recent, retention trim — so playback, retention
 * policy and UI limits stay outside the database layer.
 *
 * Every query is append-only friendly: rows are never updated, and the
 * newest-first ordering breaks ties on the auto-generated id so two events
 * written in the same millisecond still have a deterministic order.
 */
@Dao
interface HistoryDao {

    /**
     * Reactive observation of the most recent events, newest first, capped at
     * [limit] rows. Bounding the query is what keeps the Home row and the Auto
     * node cheap even as the log grows to the retention ceiling.
     */
    @Query(
        """
        SELECT * FROM history
        ORDER BY played_at DESC, id DESC
        LIMIT :limit
        """
    )
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    /**
     * Append one event. Returns the new row id. A genuine log append: no
     * REPLACE/IGNORE, so a track replayed later appends a second row.
     */
    @Insert
    suspend fun insert(entry: HistoryEntity): Long

    /**
     * Retention trim: delete every row outside the newest [keep] events,
     * keeping the table bounded. Ordering matches [observeRecent] so "newest"
     * means the same thing in both directions.
     */
    @Query(
        """
        DELETE FROM history
        WHERE id NOT IN (
            SELECT id FROM history
            ORDER BY played_at DESC, id DESC
            LIMIT :keep
        )
        """
    )
    suspend fun deleteBeyondNewest(keep: Int)

    /** Number of stored events (retention assertions and diagnostics). */
    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int
}
