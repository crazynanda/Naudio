package com.naudio.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.naudio.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

/** Minimal DAO for the favorites/persistence layer. No repository, no UI here. */
@Dao
interface TrackDao {

    /** Reactive observation of favorited tracks, newest-saved first. */
    @Query(
        """
        SELECT * FROM tracks
        WHERE is_favorite = 1
        ORDER BY saved_at DESC
        """
    )
    fun observeFavorites(): Flow<List<TrackEntity>>

    /** Insert or replace a full track row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: TrackEntity)

    /** Update favorite state + savedAt using BOTH providerId and trackId. */
    @Query(
        """
        UPDATE tracks
        SET is_favorite = :isFavorite,
            saved_at = :savedAt
        WHERE id = :trackId
          AND provider_id = :providerId
        """
    )
    suspend fun updateFavorite(
        providerId: String,
        trackId: String,
        isFavorite: Boolean,
        savedAt: Long?,
    )
}
