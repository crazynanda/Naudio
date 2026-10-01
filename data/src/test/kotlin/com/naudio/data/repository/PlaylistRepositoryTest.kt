package com.naudio.data.repository

import com.naudio.core.database.dao.PlaylistDao
import com.naudio.core.database.dao.PlaylistWithCount
import com.naudio.core.database.dao.TrackDao
import com.naudio.core.database.entity.PlaylistEntity
import com.naudio.core.database.entity.PlaylistTrackCrossRef
import com.naudio.core.database.entity.TrackEntity
import com.naudio.core.model.Playlist
import com.naudio.core.model.Track
import com.naudio.data.mapper.PlaylistMapper
import com.naudio.data.mapper.TrackMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRepositoryTest {

    /** Shared DAO-op ordering log (asserts the transactional write order). */
    private val opLog = mutableListOf<String>()
    private val trackDao = PlaylistRepoFakeTrackDao(opLog)
    private val playlistDao = PlaylistRepoFakePlaylistDao(opLog, trackDao)
    private val repository = PlaylistRepository(
        playlistDao = playlistDao,
        trackDao = trackDao,
        transactions = sequentialTransactions,
    )

    private val itunesTrack = Track("1440847780", "itunes", "One More Time", "Daft Punk", durationMs = 320_000L)
    private val localTrack = Track("t1", "local", "Test Tone", "Naudio", durationMs = 3_000L)

    @Test
    fun `createPlaylist trims the name and stores it`() = runTest {
        val id = repository.createPlaylist("  Road trip  ")
        val playlist = repository.getPlaylist(id)
        assertEquals("Road trip", playlist?.name)
        assertEquals(0, playlist?.trackCount)
    }

    @Test
    fun `observePlaylists emits mapped playlists with counts`() = runTest {
        val id = repository.createPlaylist("Favorites 2")
        repository.addTrackToPlaylist(id, itunesTrack)

        val playlists = repository.observePlaylists().first()
        assertEquals(1, playlists.size)
        assertEquals("Favorites 2", playlists[0].name)
        assertEquals(1, playlists[0].trackCount)
    }

    @Test
    fun `getPlaylist returns null for unknown ids`() = runTest {
        assertNull(repository.getPlaylist(999L))
    }

    @Test
    fun `renamePlaylist updates the name and keeps the id`() = runTest {
        val id = repository.createPlaylist("Before")
        repository.renamePlaylist(id, "After")
        val playlist = repository.getPlaylist(id)
        assertEquals("After", playlist?.name)
    }

    @Test
    fun `deletePlaylist removes the playlist`() = runTest {
        val id = repository.createPlaylist("Doomed")
        repository.deletePlaylist(id)
        assertNull(repository.getPlaylist(id))
        assertTrue(repository.observePlaylists().first().isEmpty())
    }

    /**
     * The core persistence rule: the TrackEntity row must exist BEFORE the
     * playlist membership is inserted, inside one transaction. The sequential
     * transaction runner preserves the DAO op order, so the assertion is exact.
     */
    @Test
    fun `adding a track persists the TrackEntity before the membership transactionally`() = runTest {
        val id = repository.createPlaylist("Order matters")
        opLog.clear()

        repository.addTrackToPlaylist(id, itunesTrack)

        assertEquals(
            listOf("upsertTrack", "appendTrack"),
            opLog,
        )
        // Both writes landed.
        assertNotNull(trackDao.byIds(listOf("itunes"), listOf("1440847780")).firstOrNull())
        assertEquals(1, repository.observePlaylistTracks(id).first().size)
    }

    @Test
    fun `adding an existing track refreshes metadata without clobbering favorite state`() = runTest {
        val id = repository.createPlaylist("Known track")
        // Pre-existing favorited row (as if favorited earlier).
        trackDao.upsertTrack(
            TrackMapper.toEntity(itunesTrack).copy(isFavorite = true, savedAt = 42L),
        )

        repository.addTrackToPlaylist(id, Track("1440847780", "itunes", "One More Time (live)", "Daft Punk", durationMs = 999_000L))

        val row = trackDao.byIds(listOf("itunes"), listOf("1440847780")).single()
        assertEquals("One More Time (live)", row.title) // metadata refreshed
        assertTrue(row.isFavorite) // favorite state preserved
        assertEquals(42L, row.savedAt)
    }

    @Test
    fun `adding a brand new track inserts it unfavorited`() = runTest {
        val id = repository.createPlaylist("New track")
        repository.addTrackToPlaylist(id, localTrack)
        val row = trackDao.byIds(listOf("local"), listOf("t1")).single()
        assertEquals(false, row.isFavorite)
        assertEquals(null, row.savedAt)
    }

    @Test
    fun `observePlaylistTracks returns tracks in saved order`() = runTest {
        val id = repository.createPlaylist("Ordered")
        repository.addTrackToPlaylist(id, itunesTrack)
        repository.addTrackToPlaylist(id, localTrack)

        val tracks = repository.observePlaylistTracks(id).first()
        assertEquals(listOf(itunesTrack.id, localTrack.id), tracks.map { it.id })
        assertEquals(listOf("itunes", "local"), tracks.map { it.providerId })
    }

    @Test
    fun `removeTrackFromPlaylist deletes only the membership`() = runTest {
        val id = repository.createPlaylist("Removal")
        repository.addTrackToPlaylist(id, itunesTrack)
        repository.addTrackToPlaylist(id, localTrack)

        repository.removeTrackFromPlaylist(id, "itunes", "1440847780")

        val tracks = repository.observePlaylistTracks(id).first()
        assertEquals(listOf("t1"), tracks.map { it.id })
        // The underlying TrackEntity survives (never cascade-deleted).
        assertNotNull(trackDao.byIds(listOf("itunes"), listOf("1440847780")).firstOrNull())
    }

    @Test
    fun `duplicate adds keep a single occurrence`() = runTest {
        val id = repository.createPlaylist("No dupes")
        repository.addTrackToPlaylist(id, itunesTrack)
        repository.addTrackToPlaylist(id, itunesTrack)
        assertEquals(1, repository.observePlaylistTracks(id).first().size)
    }

    @Test
    fun `mixed provider tracks coexist with composite identity`() = runTest {
        val id = repository.createPlaylist("Mixed")
        repository.addTrackToPlaylist(id, Track("42", "local", "Local 42", "A"))
        repository.addTrackToPlaylist(id, Track("42", "itunes", "Itunes 42", "B"))

        val tracks = repository.observePlaylistTracks(id).first()
        assertEquals(
            listOf("local" to "42", "itunes" to "42"),
            tracks.map { it.providerId to it.id },
        )
    }

    @Test
    fun `mapper round-trips playlist rows`() = runTest {
        val entity = PlaylistEntity(id = 7L, name = "Mapped", createdAt = 123L)
        val domain = PlaylistMapper.toDomain(entity, trackCount = 3)
        assertEquals(Playlist(id = 7L, name = "Mapped", trackCount = 3), domain)

        val back = PlaylistMapper.toEntity(domain, createdAt = 123L)
        assertEquals(entity, back)
    }
}

/**
 * Sequential stand-in for the real transaction runner: constructed directly
 * (TransactionRunner is final, so it is instantiated — never subclassed) and
 * simply executes the block, preserving the DAO call order the repository
 * performs inside the transaction.
 */
private val sequentialTransactions = TransactionRunner { block -> block() }

/**
 * In-memory PlaylistDao mirroring the Room implementation's semantics:
 * composite-key IGNORE inserts, position-ordered reads, transactional append.
 */
private class PlaylistRepoFakePlaylistDao(
    private val opLog: MutableList<String>,
    private val trackDao: PlaylistRepoFakeTrackDao,
) : PlaylistDao {
    val playlists = mutableMapOf<Long, PlaylistEntity>()
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
        playlists.values.map(::withCount).sortedByDescending { it.playlist.createdAt }
    }

    override fun observePlaylistWithCount(playlistId: Long): Flow<PlaylistWithCount?> =
        observePlaylistsWithCount().map { rows -> rows.firstOrNull { it.playlist.id == playlistId } }

    override suspend fun playlistWithCount(playlistId: Long): PlaylistWithCount? =
        playlists[playlistId]?.let(::withCount)

    override suspend fun insertPlaylist(playlist: PlaylistEntity): Long {
        val id = nextId++
        playlists[id] = playlist.copy(id = id)
        publish()
        return id
    }

    override suspend fun updatePlaylist(playlist: PlaylistEntity) {
        playlists[playlist.id] = playlist
        publish()
    }

    override suspend fun deletePlaylist(playlistId: Long) {
        refs.removeAll { it.playlistId == playlistId }
        playlists.remove(playlistId)
        publish()
    }

    override suspend fun deletePlaylistRow(playlistId: Long) {
        playlists.remove(playlistId)
        publish()
    }

    override suspend fun insertTrackRef(ref: PlaylistTrackCrossRef): Long {
        // IGNORE semantics on the composite (playlist, provider, track) key.
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
        opLog += "appendTrack"
        // Mirrors the real DAO: duplicate → -1 no-op, else MAX(position) + 1.
        if (countTrack(playlistId, providerId, trackId) > 0) return -1L
        val position = (maxPosition(playlistId) ?: -1) + 1
        return insertTrackRef(PlaylistTrackCrossRef(playlistId, providerId, trackId, position))
    }

    override suspend fun countTrack(playlistId: Long, providerId: String, trackId: String): Int =
        refs.count { it.playlistId == playlistId && it.providerId == providerId && it.trackId == trackId }

    override suspend fun maxPosition(playlistId: Long): Int? =
        refs.filter { it.playlistId == playlistId }.maxOfOrNull { it.position }
}

/** REPLACE-faithful in-memory TrackDao for playlist tests. */
private class PlaylistRepoFakeTrackDao(private val opLog: MutableList<String>) : TrackDao {
    val rows = mutableMapOf<Pair<String, String>, TrackEntity>()
    private val state = MutableStateFlow<List<TrackEntity>>(emptyList())

    private fun publish() {
        state.value = rows.values.filter { it.isFavorite }.sortedByDescending { it.savedAt ?: 0L }
    }

    override fun observeFavorites(): Flow<List<TrackEntity>> = state

    override suspend fun byIds(providerIds: List<String>, trackIds: List<String>): List<TrackEntity> =
        rows.values.filter { it.providerId in providerIds && it.id in trackIds }

    override suspend fun upsertTrack(track: TrackEntity) {
        opLog += "upsertTrack"
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
