package com.naudio.data.repository

import com.naudio.core.database.dao.QueueDao
import com.naudio.core.database.dao.TrackDao
import com.naudio.core.database.entity.QueueStateEntity
import com.naudio.core.model.Track
import com.naudio.data.mapper.QueueMapper
import com.naudio.data.mapper.TrackMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/** Persisted snapshot of the playback queue plus its playback position. */
data class PersistedQueue(
    /** Queue tracks in playback order. */
    val tracks: List<Track>,
    /** Last played position into [tracks]; null when nothing was ever played. */
    val currentIndex: Int?,
)

/**
 * Single entry point for persistent-queue storage (M10). Owns the queue tables
 * and keeps Room types out of the presentation layer.
 *
 * Queue tracks are mirrored into `tracks` with their favorite state preserved:
 * an already-known row keeps `isFavorite`/`savedAt` untouched, a brand-new row
 * is inserted with `isFavorite = false`. Favorite state itself remains the
 * exclusive domain of [FavoritesRepository] — this class never edits it.
 */
class QueueRepository(
    private val queueDao: QueueDao,
    private val trackDao: TrackDao,
) {

    /**
     * Replace the whole queue with [tracks] and persist the playback position
     * [currentIndex]. A null index, an empty queue, or an out-of-range index
     * all persist as "no position". Every track is mirrored into `tracks`
     * (see [persistQueueTracks] — favorite state is never destroyed).
     */
    suspend fun replaceQueue(tracks: List<Track>, currentIndex: Int?) {
        queueDao.clearQueue()
        queueDao.insertAll(
            tracks.mapIndexed { index, track -> QueueMapper.toEntity(index, track) },
        )
        persistQueueTracks(tracks)
        val validIndex = currentIndex?.takeIf { tracks.isNotEmpty() && it in tracks.indices }
        setCurrentIndex(validIndex)
    }

    /**
     * Persist [tracks] into the `tracks` table without clobbering favorite
     * state: known rows are re-upserted carrying their existing
     * `isFavorite`/`savedAt` over to the refreshed metadata; unknown rows are
     * inserted unfavorited.
     */
    private suspend fun persistQueueTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val existing = trackDao
            .byIds(
                providerIds = tracks.map { it.providerId }.distinct(),
                trackIds = tracks.map { it.id }.distinct(),
            )
            .associateBy { it.providerId to it.id }
        tracks.forEach { track ->
            val current = existing[track.providerId to track.id]
            trackDao.upsertTrack(
                if (current != null) {
                    // Refresh metadata; carry the favorite state over untouched.
                    current.copy(
                        title = track.title,
                        artist = track.artist,
                        durationMs = track.durationMs,
                        album = track.album,
                        artworkUrl = track.artworkUrl,
                    )
                } else {
                    TrackMapper.toEntity(track)
                },
            )
        }
    }

    /** Emits the queue + position whenever either changes (distinct). */
    fun observeQueue(): Flow<PersistedQueue> =
        combine(queueDao.observeQueue(), queueDao.observeState()) { items, state ->
            PersistedQueue(
                tracks = items.map(QueueMapper::toDomain),
                currentIndex = state?.currentIndex,
            )
        }.distinctUntilChanged()

    /** Update the persisted playback position (null = no position). */
    suspend fun setCurrentIndex(index: Int?) {
        queueDao.upsertState(QueueStateEntity(currentIndex = index))
    }

    /** Position currently persisted, for one-shot reads at startup. */
    suspend fun currentIndex(): Int? = queueDao.observeState().first()?.currentIndex
}
