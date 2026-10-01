package com.naudio.app.ui

import com.naudio.core.database.dao.PlaylistDao
import com.naudio.core.database.dao.PlaylistWithCount
import com.naudio.core.database.dao.TrackDao
import com.naudio.core.database.entity.PlaylistEntity
import com.naudio.core.database.entity.PlaylistTrackCrossRef
import com.naudio.core.database.entity.TrackEntity
import com.naudio.core.model.Track
import com.naudio.data.repository.PlaylistRepository
import com.naudio.data.repository.TransactionRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic PlaylistViewModel tests (M13): state mapping, create/rename/
 * delete flows, add/remove track, and the create-then-add convenience intent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var playlistDao: PlaylistVmFakePlaylistDao
    private lateinit var trackDao: PlaylistVmFakeTrackDao
    private lateinit var viewModel: PlaylistViewModel

    private val itunesTrack = Track("1440847780", "itunes", "One More Time", "Daft Punk", durationMs = 320_000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val opLog = mutableListOf<String>()
        trackDao = PlaylistVmFakeTrackDao(opLog)
        playlistDao = PlaylistVmFakePlaylistDao(opLog, trackDao)
        viewModel = PlaylistViewModel(
            PlaylistRepository(playlistDao, trackDao, TransactionRunner { block -> block() }),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** uiState uses WhileSubscribed; tests subscribe it on the test scope. */
    private fun collectInBackground(flow: StateFlow<*>, scope: CoroutineScope) {
        scope.launch { flow.collect {} }
    }

    @Test
    fun `initial state has no playlists`() = runTest {
        collectInBackground(viewModel.uiState, backgroundScope)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.playlists.isEmpty())
    }

    @Test
    fun `createPlaylist adds a playlist with a trimmed name`() = runTest {
        collectInBackground(viewModel.uiState, backgroundScope)
        viewModel.createPlaylist("  Road trip  ")
        advanceUntilIdle()

        val playlists = viewModel.uiState.value.playlists
        assertEquals(1, playlists.size)
        assertEquals("Road trip", playlists[0].name)
        assertEquals(0, playlists[0].trackCount)
    }

    @Test
    fun `createPlaylist ignores blank names`() = runTest {
        collectInBackground(viewModel.uiState, backgroundScope)
        viewModel.createPlaylist("   ")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.playlists.isEmpty())
    }

    @Test
    fun `addTrackToPlaylist increases the emitted track count`() = runTest {
        collectInBackground(viewModel.uiState, backgroundScope)
        val id = createPlaylistAndAwait("Mix")
        advanceUntilIdle()

        viewModel.addTrackToPlaylist(id, itunesTrack)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.playlists.single().trackCount)
    }

    @Test
    fun `detailState exposes the playlist and its ordered tracks`() = runTest {
        collectInBackground(viewModel.detailState, backgroundScope)
        val id = createPlaylistAndAwait("Mix")
        viewModel.addTrackToPlaylist(id, itunesTrack)
        advanceUntilIdle()

        viewModel.openPlaylist(id)
        advanceUntilIdle()

        val detail = viewModel.detailState.value
        assertEquals("Mix", detail.playlist?.name)
        assertEquals(1, detail.tracks.size)
        assertEquals("One More Time", detail.tracks[0].title)
        assertFalse(detail.isEmpty)
    }

    @Test
    fun `removeTrack updates the detail state but keeps the track row`() = runTest {
        collectInBackground(viewModel.uiState, backgroundScope)
        collectInBackground(viewModel.detailState, backgroundScope)
        val id = createPlaylistAndAwait("Mix")
        viewModel.addTrackToPlaylist(id, itunesTrack)
        advanceUntilIdle()
        viewModel.openPlaylist(id)
        advanceUntilIdle()
        assertFalse(viewModel.detailState.value.isEmpty)

        viewModel.removeTrack(id, itunesTrack)
        advanceUntilIdle()

        assertTrue(viewModel.detailState.value.isEmpty)
        assertEquals(0, viewModel.uiState.value.playlists.single().trackCount)
        // The underlying track entity survives.
        assertTrue(trackDao.rows.containsKey("itunes" to "1440847780"))
    }

    @Test
    fun `renamePlaylist changes the detail playlist name`() = runTest {
        collectInBackground(viewModel.detailState, backgroundScope)
        val id = createPlaylistAndAwait("Before")
        viewModel.openPlaylist(id)
        advanceUntilIdle()

        viewModel.renamePlaylist(id, "After")
        advanceUntilIdle()

        assertEquals("After", viewModel.detailState.value.playlist?.name)
    }

    @Test
    fun `deletePlaylist removes it from state and closes the open detail`() = runTest {
        collectInBackground(viewModel.uiState, backgroundScope)
        collectInBackground(viewModel.detailState, backgroundScope)
        val id = createPlaylistAndAwait("Doomed")
        viewModel.openPlaylist(id)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.playlists.size)

        viewModel.deletePlaylist(id)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.playlists.isEmpty())
        assertEquals(null, viewModel.detailState.value.playlist)
    }

    @Test
    fun `createPlaylistAndAddTrack persists the track and the membership`() = runTest {
        collectInBackground(viewModel.uiState, backgroundScope)
        collectInBackground(viewModel.detailState, backgroundScope)
        viewModel.createPlaylistAndAddTrack("Fresh", itunesTrack)
        advanceUntilIdle()

        val playlist = viewModel.uiState.value.playlists.single()
        assertEquals("Fresh", playlist.name)
        assertEquals(1, playlist.trackCount)
        // The track row exists (persistence rule) — visible via the detail.
        viewModel.openPlaylist(playlist.id)
        advanceUntilIdle()
        assertEquals(listOf(itunesTrack), viewModel.detailState.value.tracks)
    }

    /** Creates a playlist and returns its id once the insert completed. */
    private suspend fun createPlaylistAndAwait(name: String): Long = PlaylistRepository(
        playlistDao,
        trackDao,
        TransactionRunner { block -> block() },
    ).createPlaylist(name)
}

/** REPLACE-faithful in-memory TrackDao for playlist tests. */
private class PlaylistVmFakeTrackDao(private val opLog: MutableList<String>) : TrackDao {
    val rows = mutableMapOf<Pair<String, String>, TrackEntity>()
    private val state = MutableStateFlow<List<TrackEntity>>(emptyList())

    private fun publish() {
        state.value = rows.values.filter { it.isFavorite }.sortedByDescending { it.savedAt ?: 0L }
    }

    override fun observeFavorites() = state

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

/** In-memory PlaylistDao with append semantics matching the Room implementation. */
private class PlaylistVmFakePlaylistDao(
    private val opLog: MutableList<String>,
    private val trackDao: PlaylistVmFakeTrackDao,
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
