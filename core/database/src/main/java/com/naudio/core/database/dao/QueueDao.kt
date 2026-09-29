package com.naudio.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.naudio.core.database.entity.QueueEntity
import com.naudio.core.database.entity.QueueStateEntity
import kotlinx.coroutines.flow.Flow

/** DAO for the persistent playback queue (M10). No playback logic here. */
@Dao
interface QueueDao {

    /** Reactive observation of the queue in playback order. */
    @Query("SELECT * FROM queue_items ORDER BY order_index ASC")
    fun observeQueue(): Flow<List<QueueEntity>>

    /** Remove every queue item (a replace always starts from an empty queue). */
    @Query("DELETE FROM queue_items")
    suspend fun clearQueue()

    /** Insert queue rows; REPLACE resolves conflicts on the order_index key. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<QueueEntity>)

    /** Reactive observation of the persisted queue position (null when unset). */
    @Query("SELECT * FROM queue_state WHERE id = 0")
    fun observeState(): Flow<QueueStateEntity?>

    /** Insert or replace the single state row (id = 0). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: QueueStateEntity)
}
