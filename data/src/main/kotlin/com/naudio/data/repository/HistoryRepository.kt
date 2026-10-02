package com.naudio.data.repository

import com.naudio.core.database.dao.HistoryDao
import com.naudio.core.model.History
import com.naudio.core.model.Track
import com.naudio.data.mapper.HistoryMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Single entry point for playback-history persistence (M17).
 *
 * Responsibilities, and nothing else:
 *  - append a qualifying listening EVENT (with its metadata snapshot),
 *  - observe the newest events for the UI and the Android Auto node,
 *  - keep the log bounded by the retention limit.
 *
 * What it deliberately does NOT do: decide whether a track qualified. That is
 * the [com.naudio.app.history.HistoryTracker]'s job — this layer never
 * inspects player state, and the player never touches Room directly.
 *
 * The log is append-only: [record] always inserts, so replaying a track later
 * appends a second event rather than replacing the first. Room/Entity types
 * stay behind [HistoryMapper]; callers see the domain [History].
 */
class HistoryRepository(
    private val historyDao: HistoryDao,
    /**
     * Owns the post-insert retention cleanup. Injected (and app-lifetime in
     * production) so the trim can outlive the caller's coroutine: playback
     * must never wait for it, and it must not be cancelled if the caller —
     * e.g. a ViewModel — goes away.
     */
    private val scope: CoroutineScope,
) {

    /**
     * Emits the newest events, newest first, capped at [limit]. Defaults to
     * [RECENTLY_PLAYED_LIMIT] — enough for the Home carousel and the Auto node
     * without paging.
     */
    fun observeRecent(limit: Int = RECENTLY_PLAYED_LIMIT): Flow<List<History>> =
        historyDao.observeRecent(limit).map { entities -> entities.map(HistoryMapper::toDomain) }

    /**
     * Appends one listening event for [track], timestamped with [playedAt]
     * (wall-clock epoch millis — the monotonic clock is only used for duration
     * accounting, never for ordering).
     *
     * The retention trim is fired on [scope] instead of being awaited: once
     * the log is full, deleting the oldest rows must not add latency to
     * playback, and no background service is involved — the coroutine runs on
     * the app-lifetime scope that owns this repository.
     */
    suspend fun record(track: Track, playedAt: Long): Long {
        val id = historyDao.insert(HistoryMapper.toEntity(track, playedAt))
        scope.launch { historyDao.deleteBeyondNewest(RETENTION_LIMIT) }
        return id
    }

    /**
     * Trims the log to the newest [limit] events, awaiting completion. Used by
     * [record]'s cleanup and available for tests/diagnostics; not needed on any
     * playback path.
     */
    suspend fun enforceRetention(limit: Int = RETENTION_LIMIT) {
        historyDao.deleteBeyondNewest(limit)
    }

    /** Number of stored events. */
    suspend fun count(): Int = historyDao.count()

    companion object {
        /**
         * M17 retention ceiling: the newest 1000 events are kept, everything
         * older is deleted after each insert. Bounded, simple, and large
         * enough that Recently Played is never empty in practice.
         */
        const val RETENTION_LIMIT = 1000

        /** How many events the Home row and the Auto node read. */
        const val RECENTLY_PLAYED_LIMIT = 50
    }
}
