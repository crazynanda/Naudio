package com.naudio.app.auto

import com.naudio.core.database.dao.HistoryDao
import com.naudio.core.database.dao.PlaylistDao
import com.naudio.core.database.dao.PlaylistWithCount
import com.naudio.core.database.dao.TrackDao
import com.naudio.core.database.entity.HistoryEntity
import com.naudio.core.database.entity.PlaylistEntity
import com.naudio.core.database.entity.PlaylistTrackCrossRef
import com.naudio.core.database.entity.TrackEntity
import com.naudio.core.model.Track
import com.naudio.data.repository.FavoritesRepository
import com.naudio.data.repository.HistoryRepository
import com.naudio.data.repository.PlaylistRepository
import com.naudio.data.repository.TransactionRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M14: the Android Auto browse tree must expose the repositories' own
 * ordering — favorites by savedAt DESC, playlist tracks by position ASC —
 * without duplicating any ordering logic.
 *
 * M17 extends the same rule to Recently Played: the history repository's own
 * newest-first order, with duplicates preserved because history is an event
 * log.
 */
class AutoBrowseTreeTest {

    private val trackDao = AutoTreeFakeTrackDao()
    private val playlistDao = AutoTreeFakePlaylistDao(trackDao)
    private val historyDao = AutoTreeFakeHistoryDao()
    private val playlistRepository = PlaylistRepository(
        playlistDao = playlistDao,
        trackDao = trackDao,
        transactions = TransactionRunner { block -> block() },
    )
    // The retention trim runs on the repository's scope; a TestScope keeps it
    // queued (never racing these assertions on a real dispatcher).
    private val historyRepository = HistoryRepository(historyDao, TestScope())
    private val tree = AutoBrowseTree(
        favoritesRepository = FavoritesRepository(trackDao),
        playlistRepository = playlistRepository,
        historyRepository = historyRepository,
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
        assertTrue(tree.observeRecentHistory().first().isEmpty())
    }

    @Test
    fun `recent history is exposed newest first as playable tracks`() = runTest {
        historyDao.insert(entity(trackId = "old", playedAt = 100L))
        historyDao.insert(entity(trackId = "new", playedAt = 200L))

        val history = tree.observeRecentHistory().first()
        assertEquals(listOf("new", "old"), history.map { it.id })
        // The snapshot is projected onto the existing Track type, so Auto can
        // hand it straight to playCollection.
        assertEquals("rich new", history.first().title)
        assertEquals("itunes", history.first().providerId)
    }

    @Test
    fun `repeated plays of one track stay as separate browse entries`() = runTest {
        historyDao.insert(entity(trackId = "twice", playedAt = 100L))
        historyDao.insert(entity(trackId = "twice", playedAt = 200L))

        assertEquals(2, tree.observeRecentHistory().first().size)
    }

    private fun entity(trackId: String, playedAt: Long) = HistoryEntity(
        providerId = "itunes",
        trackId = trackId,
        title = "rich $trackId",
        artist = "Artist",
        album = "Album",
        artworkUrl = "https://example.test/$trackId.jpg",
        durationMs = 1_000L,
        playedAt = playedAt,
    )

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

/**
 * Minimal in-memory TrackDao for browse-tree tests.
 *
 * M20 reuses it from [AutoPlaybackGatewayTest], which needs the same
 * favorites/playlists/history repositories to build a real [AutoPlaybackGateway].
 */
internal class AutoTreeFakeTrackDao : TrackDao {
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

/** Minimal in-memory HistoryDao mirroring the Room append-only semantics. M20 reuses it. */
internal class AutoTreeFakeHistoryDao : HistoryDao {

    val rows = mutableListOf<HistoryEntity>()
    private val state = MutableStateFlow<List<HistoryEntity>>(emptyList())
    private var nextId = 1L

    private fun publish() {
        state.value = rows.sortedWith(
            compareByDescending<HistoryEntity> { it.playedAt }.thenByDescending { it.id },
        )
    }

    override fun observeRecent(limit: Int): Flow<List<HistoryEntity>> =
        state.map { entries -> entries.take(limit) }

    override suspend fun insert(entry: HistoryEntity): Long {
        val id = nextId++
        rows += entry.copy(id = id)
        publish()
        return id
    }

    override suspend fun deleteBeyondNewest(keep: Int) {
        val survivors = state.value.take(keep)
        rows.clear()
        rows += survivors.sortedBy { it.id }
        publish()
    }

    override suspend fun count(): Int = rows.size
}

/** Minimal in-memory PlaylistDao mirroring the Room semantics. M20 reuses it. */
internal class AutoTreeFakePlaylistDao(
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
