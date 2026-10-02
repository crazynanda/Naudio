package com.naudio.app.auto

import com.naudio.app.playback.PlaybackCoordinator
import com.naudio.core.model.Track
import com.naudio.core.player.AutoBrowseTreeProvider
import com.naudio.core.player.AutoPlaybackBridge
import com.naudio.data.repository.FavoritesRepository
import com.naudio.data.repository.HistoryRepository
import com.naudio.data.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Auto browse-tree source over the EXISTING repositories — read-only, no
 * duplicate ordering logic and no storage of its own. Favorites come from
 * [FavoritesRepository] (savedAt DESC), playlists and playlist tracks from
 * [PlaylistRepository] (newest first / position ASC), and — M17 — Recently
 * Played from the very same [HistoryRepository] the Home row reads, in its own
 * newest-first order. Repository/DAO ordering is used verbatim everywhere.
 */
class AutoBrowseTree(
    private val favoritesRepository: FavoritesRepository,
    private val playlistRepository: PlaylistRepository,
    private val historyRepository: HistoryRepository,
) : AutoBrowseTreeProvider {

    override fun observeFavorites(): Flow<List<Track>> = favoritesRepository.observeFavorites()

    override fun observePlaylists(): Flow<List<AutoBrowseTreeProvider.PlaylistNode>> =
        playlistRepository.observePlaylists().map { playlists ->
            playlists.map {
                AutoBrowseTreeProvider.PlaylistNode(
                    playlistId = it.id,
                    name = it.name,
                    trackCount = it.trackCount,
                )
            }
        }

    override fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> =
        playlistRepository.observePlaylistTracks(playlistId)

    /**
     * M17: the newest history events, projected onto [Track] from their stored
     * metadata snapshots. Duplicates are PRESERVED — history is an event log,
     * not a unique-track list.
     */
    override fun observeRecentHistory(): Flow<List<Track>> =
        historyRepository.observeRecent().map { entries -> entries.map { it.toTrack() } }
}

/**
 * The ONE playback pathway behind the Android Auto session: a thin adapter
 * that delegates to the SAME shared [PlaybackCoordinator] the mobile UI
 * drives. Playing from Auto calls [PlaybackCoordinator.setQueue] — the exact
 * entry point every mobile "play" interaction uses — so there is one queue,
 * one resolution path (just-in-time through the playback providers) and one
 * player. This class resolves no sources and creates no player and no state.
 *
 * [findTrack] backs only per-item metadata lookups: it reads the same
 * collections the browse tree exposes (favorites, playlist memberships and —
 * M17 — recent history).
 */
class AutoPlaybackGateway(
    private val coordinator: PlaybackCoordinator,
    private val favoritesRepository: FavoritesRepository,
    private val playlistRepository: PlaylistRepository,
    private val historyRepository: HistoryRepository,
) : AutoPlaybackBridge {

    override fun playCollection(tracks: List<Track>, startIndex: Int) {
        coordinator.setQueue(tracks = tracks, startIndex = startIndex)
    }

    override suspend fun findTrack(providerId: String, trackId: String): Track? {
        favoritesRepository.observeFavorites().first()
            .firstOrNull { it.providerId == providerId && it.id == trackId }
            ?.let { return it }
        val playlists = playlistRepository.observePlaylists().first()
        for (playlist in playlists) {
            playlistRepository.observePlaylistTracks(playlist.id).first()
                .firstOrNull { it.providerId == providerId && it.id == trackId }
                ?.let { return it }
        }
        // M17 fallback: a Recently Played node must resolve its own item even
        // when the track is neither favorited nor in a playlist. The snapshot
        // carries everything the MediaItem needs — no provider lookup, and
        // playback still goes through the coordinator's just-in-time path.
        historyRepository.observeRecent().first()
            .firstOrNull { it.providerId == providerId && it.trackId == trackId }
            ?.let { return it.toTrack() }
        return null
    }

    override fun skipToNext() = coordinator.skipToNext()

    override fun skipToPrevious() = coordinator.skipToPrevious()
}
