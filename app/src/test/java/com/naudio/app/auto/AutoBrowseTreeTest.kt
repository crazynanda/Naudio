package com.naudio.app.auto

import com.naudio.core.database.dao.PlaylistDao
import com.naudio.core.database.dao.PlaylistWithCount
import com.naudio.core.database.dao.TrackDao
import com.naudio.core.database.entity.PlaylistEntity
import com.naudio.core.database.entity.PlaylistTrackCrossRef
import com.naudio.core.database.entity.TrackEntity
import com.naudio.core.model.Track
import com.naudio.data.repository.FavoritesRepository
import com.naudio.data.repository.PlaylistRepository
import com.naudio.data.repository.TransactionRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M14: the Android Auto browse tree must expose the repositories' own
 * ordering — favorites by savedAt DESC, playlist tracks by position ASC —
 * without duplicating any ordering logic.
 */
class AutoBrowseTreeTest {

    private val trackDao = AutoTreeFakeTrackDao()
    private val playlistDao = AutoTreeFakePlaylistDao(trackDao)
    private val playlistRepository = PlaylistRepository(
        playlistDao = playlistDao,
        trackDao = trackDao,
        transactions = TransactionRunner { block -> block() },
    )
    private val tree = AutoBrowseTree(
        favoritesRepository = FavoritesRepository(trackDao),
        playlistRepository = playlistRepository,
    )

    @Test
    fun `favorites are exposed in the repository order`() = runTest {
        trackDao.upsertTrack(entity("old", isFavorite = true, savedAt = 100L))
        trackDao.upsertTrack(entity("new", isFavorite = true, savedAt = 200L))

        val favorites = tree.observeFavorites().first()
        assertEquals(listOf("new", "old"), favorites.map { it.id })
    }

    @Test
    fun `playlists are exposed with counts in repository order`() = runTest {
        playlistRepository.createPlaylist("First")
        // Distinct createdAt millis: "newest first" must be unambiguous.
        Thread.sleep(5)
        playlistRepository.createPlaylist("Second")

        val playlists = tree.observePlaylists().first()
        assertEquals(listOf("Second", "First"), playlists.map { it.name })
        assertEquals(0, playlists[0].trackCount)
    }

    @Test
    fun `playlist tracks are exposed in position order`() = runTest {
        val playlistId = playlistRepository.createPlaylist("Mix")
        playlistRepository.addTrackToPlaylist(playlistId, track("first"))
        playlistRepository.addTrackToPlaylist(playlistId, track("second"))

        val tracks = tree.observePlaylistTracks(playlistId).first()
        assertEquals(listOf("first", "second"), tracks.map { it.id })
    }

    @Test
    fun `empty collections stay empty`() = runTest {
        assertTrue(tree.observeFavorites().first().isEmpty())
        assertTrue(tree.observePlaylists().first().isEmpty())
    }

    private fun track(id: String): Track = Track(
        id = id,
        providerId = "local",
        title = "Track $id",
        artist = "Artist",
        durationMs = 1_000L,
    )

    private fun entity(id: String, isFavorite: Boolean = false, savedAt: Long? = null): TrackEntity =
        TrackEntity(
            id = id,
            providerId = "local",
            title = "Track $id",
            artist = "Artist",
            durationMs = 1_000L,
            isFavorite = isFavorite,
            savedAt = savedAt,
        )
}

/** Minimal in-memory TrackDao for browse-tree tests. */
private class AutoTreeFakeTrackDao : TrackDao {
    val rows = linkedMapOf<Pair<String, String>, TrackEntity>()
    private val state = MutableStateFlow<List<TrackEntity>>(emptyList())

    private fun publish() {
        state.value = rows.values.filter { it.isFavorite }.sortedByDescending { it.savedAt ?: 0L }
    }

    override fun observeFavorites() = state

    override suspend fun byIds(providerIds: List<String>, trackIds: List<String>): List<TrackEntity> =
        rows.values.filter { it.providerId in providerIds && it.id in trackIds }

    override suspend fun upsertTrack(track: TrackEntity) {
        rows[track.providerId to track.id] = track
        publish()
    }

    override suspend fun updateFavorite(providerId: String, trackId: String, isFavorite: Boolean, savedAt: Long?) {
        val key = providerId to trackId
        val existing = rows[key] ?: return
        rows[key] = existing.copy(isFavorite = isFavorite, savedAt = savedAt)
        publish()
    }
}

/** Minimal in-memory PlaylistDao mirroring the Room semantics. */
private class AutoTreeFakePlaylistDao(
    private val trackDao: AutoTreeFakeTrackDao,
) : PlaylistDao {
    val playlists = mutableListOf<PlaylistEntity>()
    val refs = mutableListOf<PlaylistTrackCrossRef>()
    private var nextId = 1L
    private val state = MutableStateFlow(0)

    private fun publish() {
        state.value += 1
    }

    private fun withCount(pl: PlaylistEntity) = PlaylistWithCount(
        playlist = pl,
        trackCount = refs.count { it.playlistId == pl.id },
    )

    override fun observePlaylistsWithCount(): Flow<List<PlaylistWithCount>> = state.map {
        playlists.map(::withCount).sortedByDescending { it.playlist.createdAt }
    }

    override fun observePlaylistWithCount(playlistId: Long): Flow<PlaylistWithCount?> =
        observePlaylistsWithCount().map { rows -> rows.firstOrNull { it.playlist.id == playlistId } }

    override suspend fun playlistWithCount(playlistId: Long): PlaylistWithCount? =
        playlists.firstOrNull { it.id == playlistId }?.let(::withCount)

    override suspend fun insertPlaylist(playlist: PlaylistEntity): Long {
        val id = nextId++
        playlists += playlist.copy(id = id)
        publish()
        return id
    }

    override suspend fun updatePlaylist(playlist: PlaylistEntity) {
        val index = playlists.indexOfFirst { it.id == playlist.id }
        if (index >= 0) playlists[index] = playlist
        publish()
    }

    override suspend fun deletePlaylist(playlistId: Long) {
        refs.removeAll { it.playlistId == playlistId }
        playlists.removeAll { it.id == playlistId }
        publish()
    }

    override suspend fun deletePlaylistRow(playlistId: Long) {
        playlists.removeAll { it.id == playlistId }
        publish()
    }

    override suspend fun insertTrackRef(ref: PlaylistTrackCrossRef): Long {
        val duplicate = refs.any {
            it.playlistId == ref.playlistId && it.providerId == ref.providerId && it.trackId == ref.trackId
        }
        if (duplicate) return -1L
        refs += ref
        publish()
        return 1L
    }

    override suspend fun deleteTrackRef(playlistId: Long, providerId: String, trackId: String) {
        refs.removeAll { it.playlistId == playlistId && it.providerId == providerId && it.trackId == trackId }
        publish()
    }

    override suspend fun clearTracks(playlistId: Long) {
        refs.removeAll { it.playlistId == playlistId }
        publish()
    }

    override fun observeTracks(playlistId: Long): Flow<List<TrackEntity>> = state.map {
        refs.asSequence()
            .filter { it.playlistId == playlistId }
            .sortedBy { it.position }
            .mapNotNull { ref -> trackDao.rows[ref.providerId to ref.trackId] }
            .toList()
    }

    override suspend fun appendTrack(playlistId: Long, providerId: String, trackId: String): Long {
        if (refs.any { it.playlistId == playlistId && it.providerId == providerId && it.trackId == trackId }) {
            return -1L
        }
        val position = (refs.filter { it.playlistId == playlistId }.maxOfOrNull { it.position } ?: -1) + 1
        return insertTrackRef(PlaylistTrackCrossRef(playlistId, providerId, trackId, position))
    }

    override suspend fun countTrack(playlistId: Long, providerId: String, trackId: String): Int =
        refs.count { it.playlistId == playlistId && it.providerId == providerId && it.trackId == trackId }

    override suspend fun maxPosition(playlistId: Long): Int? =
        refs.filter { it.playlistId == playlistId }.maxOfOrNull { it.position }
}
