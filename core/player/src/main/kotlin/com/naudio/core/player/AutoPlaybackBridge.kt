package com.naudio.core.player

import com.naudio.core.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * Read-only view of the media library for the Android Auto browse tree.
 *
 * Implemented by the app layer (:app owns the repositories) and consumed by
 * the session callback in :core:player. Flows are collected only while the
 * library session is alive — no second queue, no second player and no second
 * coordinator is created by any implementation.
 */
interface AutoBrowseTreeProvider {

    /** Every favorite, in the repository's own order (savedAt DESC). */
    fun observeFavorites(): Flow<List<Track>>

    /** Every playlist, in the repository's own order (newest first). */
    fun observePlaylists(): Flow<List<PlaylistNode>>

    /** One playlist's tracks in the repository's own ordering (position ASC). */
    fun observePlaylistTracks(playlistId: Long): Flow<List<Track>>

    /**
     * M17: the most recent playback-history EVENTS, newest first, projected
     * onto [Track] from their stored metadata snapshots.
     *
     * Returning [Track]s (rather than a separate history type) is deliberate:
     * the browse tree stays collection-shaped, and selecting an entry reuses
     * the identical [AutoPlaybackBridge.playCollection] path Favorites and
     * playlists already use. The log is an event log, so a track played in two
     * sessions appears twice.
     */
    fun observeRecentHistory(): Flow<List<Track>>

    /** Display data for a playlist node. */
    data class PlaylistNode(val playlistId: Long, val name: String, val trackCount: Int)
}

/**
 * The ONE playback pathway behind the media session. The app implements this
 * by delegating to the existing, shared [PlaybackCoordinator] (see
 * AutoPlaybackGateway in :app) — nothing here creates a player, a queue or
 * any playback state of its own.
 *
 * The contract mirrors how the mobile UI plays:
 *
 *  - [playCollection] replaces the persistent queue with [tracks] and starts
 *    at [startIndex] — the identical coordinator entry point the app's
 *    "Play all" buttons use.
 *  - [skipToNext]/[skipToPrevious] move the shared queue for every surface.
 *
 * Track lookup ([findTrack]) only backs per-item metadata requests; playback
 * itself never needs source resolution here — resolution stays exactly where
 * it is today, inside the coordinator's just-in-time path.
 */
interface AutoPlaybackBridge {

    /**
     * Play [tracks] starting at [startIndex] through the existing queue
     * mechanism. Unresolvable items are skipped by the coordinator exactly as
     * for mobile-initiated playback.
     */
    fun playCollection(tracks: List<Track>, startIndex: Int)

    /** Track lookup by composite identity (per-item metadata requests). */
    suspend fun findTrack(providerId: String, trackId: String): Track?

    /** Delegated queue commands — same coordinator the mobile UI drives. */
    fun skipToNext()
    fun skipToPrevious()
}
