package com.naudio.data.repository

import com.naudio.core.database.dao.TrackDao
import com.naudio.core.model.Track
import com.naudio.data.mapper.TrackMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Single entry point for favorites persistence.
 *
 * Focuses on favorite state only and keeps Room/Entity types out of the UI
 * and domain layers — the domain sees immutable [Track] values, the DAO
 * sees [TrackEntity] through the mapper.
 */
class FavoritesRepository(private val trackDao: TrackDao) {

    /** Emits the current favorite list; ordered by savedAt DESC as reported by the DAO. */
    fun observeFavorites(): Flow<List<Track>> =
        trackDao.observeFavorites().map { entities -> entities.map(TrackMapper::toDomain) }

    /** Toggle a track's favorite state.
     *
     * - Favorite→favorite re-upserts the full entity (keeps metadata intact).
     * - Favorite→unfavorite updates only isFavorite (=false) and savedAt(=null).
     */
    suspend fun toggleFavorite(track: Track, isFavorite: Boolean) {
        if (isFavorite) {
            // Favorite: upsert the full metadata so nothing is ever erased.
            trackDao.upsertTrack(TrackMapper.toEntity(track))
        } else {
            // Unfavorite: update only the favorite state and clear savedAt.
            trackDao.updateFavorite(
                providerId = track.providerId,
                trackId = track.id,
                isFavorite = false,
                savedAt = null,
            )
        }
    }
}
