package com.naudio.data.repository

import com.naudio.core.database.dao.QueueDao
import com.naudio.core.database.dao.TrackDao
import com.naudio.core.database.entity.QueueEntity
import com.naudio.core.database.entity.QueueStateEntity
import com.naudio.core.database.entity.TrackEntity
import com.naudio.core.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the persistent-queue repository: ordered replace,
 * position persistence, track mirroring that never destroys favorite state,
 * and the observeQueue snapshot.
 */
class QueueRepositoryTest {

    private val queueDao = FakeQueueDao()
    private val trackDao = FakeTrackDao()
    private val repository = QueueRepository(queueDao, trackDao)

    private fun track(id: String, providerId: String = "local", title: String = "T$id") =
        Track(id = id, providerId = providerId, title = title, artist = "Artist", durationMs = 1000L)

    @Test
    fun `replaceQueue writes ordered rows, mirrors tracks and persists the index`() = runTest {
        val a = track("a")
        val b = track("b")

        repository.replaceQueue(listOf(a, b), currentIndex = 1)

        assertEquals(listOf(0, 1), queueDao.items.value.map { it.orderIndex })
        assertEquals("a", queueDao.items.value[0].trackId)
        assertEquals("b", queueDao.items.value[1].trackId)
        assertEquals(1, queueDao.state.value?.currentIndex)
        // Both tracks are mirrored into the tracks table, unfavorited.
        assertEquals(2, trackDao.rows.size)
        assertFalse(trackDao.rows["local" to "a"]!!.isFavorite)
    }

    @Test
    fun `replaceQueue with an out-of-range index persists no position`() = runTest {
        repository.replaceQueue(listOf(track("a")), currentIndex = 5)
        assertNull(queueDao.state.value?.currentIndex)
    }

    @Test
    fun `replaceQueue with a null index persists no position`() = runTest {
        repository.replaceQueue(listOf(track("a")), currentIndex = null)
        assertNull(queueDao.state.value?.currentIndex)
    }

    @Test
    fun `replaceQueue with an empty list clears the queue and the position`() = runTest {
        repository.replaceQueue(listOf(track("a")), currentIndex = 0)
        repository.replaceQueue(emptyList(), currentIndex = 0)

        assertTrue(queueDao.items.value.isEmpty())
        assertNull(queueDao.state.value?.currentIndex)
    }

    @Test
    fun `replaceQueue preserves the favorite state of an already-known track`() = runTest {
        // A favorited row already exists with stale metadata.
        trackDao.upsertTrack(
            TrackEntity(
                id = "a",
                providerId = "local",
                title = "Old title",
                artist = "Old artist",
                durationMs = 1L,
                isFavorite = true,
                savedAt = 99L,
            ),
        )

        repository.replaceQueue(listOf(track("a", title = "New title")), currentIndex = 0)

        val row = trackDao.rows["local" to "a"]!!
        // Metadata refreshed, favorite state untouched.
        assertEquals("New title", row.title)
        assertTrue(row.isFavorite)
        assertEquals(99L, row.savedAt)
    }

    @Test
    fun `replaceQueue inserts unknown tracks without a favorite flag`() = runTest {
        repository.replaceQueue(listOf(track("new")), currentIndex = 0)

        val row = trackDao.rows["local" to "new"]!!
        assertFalse(row.isFavorite)
        assertNull(row.savedAt)
    }

    @Test
    fun `replaceQueue keeps favorite rows distinct across providers`() = runTest {
        trackDao.upsertTrack(
            TrackEntity(
                id = "1",
                providerId = "itunes",
                title = "iTunes one",
                artist = "Artist",
                durationMs = 5L,
                isFavorite = true,
                savedAt = 7L,
            ),
        )
        repository.replaceQueue(
            listOf(track("1", providerId = "local"), track("1", providerId = "itunes")),
            currentIndex = 0,
        )

        // The local twin must not inherit the iTunes row's favorite state,
        // and the iTunes favorite must survive the queue mirror.
        val local = trackDao.rows["local" to "1"]!!
        val itunes = trackDao.rows["itunes" to "1"]!!
        assertFalse(local.isFavorite)
        assertTrue(itunes.isFavorite)
        assertEquals(7L, itunes.savedAt)
    }

    @Test
    fun `observeQueue emits the combined snapshot and tracks state changes`() = runTest {
        repository.replaceQueue(listOf(track("a"), track("b")), currentIndex = 0)

        val initial = repository.observeQueue().first()
        assertEquals(listOf("a", "b"), initial.tracks.map { it.id })
        assertEquals(0, initial.currentIndex)

        repository.setCurrentIndex(1)
        val updated = repository.observeQueue().first()
        assertEquals(1, updated.currentIndex)
        assertEquals("b", updated.tracks[updated.currentIndex!!].id)
    }

    @Test
    fun `currentIndex returns the persisted position`() = runTest {
        assertNull(repository.currentIndex())
        repository.setCurrentIndex(2)
        assertEquals(2, repository.currentIndex())
    }

    // ------------------------------------------------------------------
    // M12: album / artwork metadata survives the queue snapshot
    // ------------------------------------------------------------------

    @Test
    fun `replaceQueue snapshots album and artwork into queue rows and mirrors tracks`() = runTest {
        val t = track("a").copy(album = "Discovery", artworkUrl = "https://x/600x600bb.jpg")

        repository.replaceQueue(listOf(t), currentIndex = 0)

        assertEquals("Discovery", queueDao.items.value[0].album)
        assertEquals("https://x/600x600bb.jpg", queueDao.items.value[0].artworkUrl)
        val row = trackDao.rows["local" to "a"]!!
        assertEquals("Discovery", row.album)
        assertEquals("https://x/600x600bb.jpg", row.artworkUrl)
    }

    @Test
    fun `replaceQueue refresh carries album and artwork to an existing favorite row`() = runTest {
        trackDao.upsertTrack(
            TrackEntity(
                id = "a",
                providerId = "local",
                title = "Old title",
                artist = "Old artist",
                durationMs = 1L,
                isFavorite = true,
                savedAt = 9L,
            ),
        )

        repository.replaceQueue(
            listOf(track("a").copy(album = "New album", artworkUrl = "content://media/external/audio/albumart/3")),
            currentIndex = 0,
        )

        val row = trackDao.rows["local" to "a"]!!
        assertEquals("New album", row.album)
        assertEquals("content://media/external/audio/albumart/3", row.artworkUrl)
        // Favorite state still untouched by the metadata refresh.
        assertTrue(row.isFavorite)
        assertEquals(9L, row.savedAt)
    }

    @Test
    fun `observeQueue restores album and artwork from the persisted snapshot`() = runTest {
        repository.replaceQueue(
            listOf(track("a", providerId = "itunes").copy(album = "Al", artworkUrl = "https://x/y.jpg")),
            currentIndex = 0,
        )

        val snapshot = repository.observeQueue().first()

        assertEquals("Al", snapshot.tracks[0].album)
        assertEquals("https://x/y.jpg", snapshot.tracks[0].artworkUrl)
    }
}

/** In-memory QueueDao with REPLACE-on-order_index semantics. */
private class FakeQueueDao : QueueDao {
    val items = MutableStateFlow<List<QueueEntity>>(emptyList())
    val state = MutableStateFlow<QueueStateEntity?>(null)

    override fun observeQueue(): Flow<List<QueueEntity>> = items

    override suspend fun clearQueue() {
        items.value = emptyList()
    }

    override suspend fun insertAll(items: List<QueueEntity>) {
        this.items.value = items.sortedBy { it.orderIndex }
    }

    override fun observeState(): Flow<QueueStateEntity?> = state

    override suspend fun upsertState(state: QueueStateEntity) {
        this.state.value = state
    }
}

/** In-memory TrackDao mirroring the Room REPLACE + composite identity. */
private class FakeTrackDao : TrackDao {
    val rows = mutableMapOf<Pair<String, String>, TrackEntity>()
    private val backing = MutableStateFlow<List<TrackEntity>>(emptyList())

    private fun publish() {
        backing.value = rows.values.toList()
    }

    override fun observeFavorites(): Flow<List<TrackEntity>> =
        backing.map { rows ->
            rows.filter { it.isFavorite }.sortedByDescending { it.savedAt ?: 0L }
        }

    override suspend fun byIds(
        providerIds: List<String>,
        trackIds: List<String>,
    ): List<TrackEntity> = backing.value.filter {
        it.providerId in providerIds && it.id in trackIds
    }

    override suspend fun upsertTrack(track: TrackEntity) {
        rows[track.providerId to track.id] = track
        publish()
    }

    override suspend fun updateFavorite(
        providerId: String,
        trackId: String,
        isFavorite: Boolean,
        savedAt: Long?,
    ) {
        val key = providerId to trackId
        val existing = rows[key] ?: return
        rows[key] = existing.copy(isFavorite = isFavorite, savedAt = savedAt)
        publish()
    }
}
