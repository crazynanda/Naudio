package com.naudio.data.repository

import com.naudio.core.database.dao.HistoryDao
import com.naudio.core.database.entity.HistoryEntity
import com.naudio.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M17 [HistoryRepository] tests: the log is append-only, newest-first and
 * bounded. Retention is what keeps "Recently Played" honest about what it
 * shows, so the trim is asserted directly as well as through [record].
 */
class HistoryRepositoryTest {

    @Test
    fun `record appends a metadata snapshot`() = runTest {
        val dao = FakeHistoryDao()
        val repository = repository(dao)

        val id = repository.record(RICH_TRACK, playedAt = 1_000L)

        assertEquals(1L, id)
        val stored = dao.rows.single()
        assertEquals("itunes", stored.providerId)
        assertEquals("rich", stored.trackId)
        assertEquals("Around the World", stored.title)
        assertEquals("Daft Punk", stored.artist)
        assertEquals("Discovery", stored.album)
        assertEquals("https://example.test/art.jpg", stored.artworkUrl)
        assertEquals(429_000L, stored.durationMs)
        assertEquals(1_000L, stored.playedAt)
    }

    @Test
    fun `the same track played twice appends two events`() = runTest {
        val dao = FakeHistoryDao()
        val repository = repository(dao)

        repository.record(TRACK, playedAt = 1L)
        repository.record(TRACK, playedAt = 2L)

        assertEquals(2, dao.rows.size)
        assertEquals(listOf(2L, 1L), repository.observeRecent().first { it.size == 2 }.map { it.id })
    }

    @Test
    fun `recent history is emitted newest first`() = runTest {
        val dao = FakeHistoryDao()
        val repository = repository(dao)
        repository.record(TRACK, playedAt = 100L)
        repository.record(TRACK, playedAt = 300L)
        repository.record(TRACK, playedAt = 200L)

        val stamps = repository.observeRecent().first { it.size == 3 }.map { it.playedAt }
        assertEquals(listOf(300L, 200L, 100L), stamps)
    }

    @Test
    fun `same millisecond ties break on insertion order`() = runTest {
        val dao = FakeHistoryDao()
        val repository = repository(dao)
        repository.record(TRACK, playedAt = 500L)
        repository.record(TRACK, playedAt = 500L)
        repository.record(TRACK, playedAt = 500L)

        val ids = repository.observeRecent().first { it.size == 3 }.map { it.id }
        assertEquals(listOf(3L, 2L, 1L), ids)
    }

    @Test
    fun `observation is capped by the requested limit`() = runTest {
        val dao = FakeHistoryDao()
        val repository = repository(dao)
        repeat(5) { repository.record(TRACK, playedAt = it.toLong()) }

        val recent = repository.observeRecent(limit = 2).first { it.size == 2 }
        assertEquals(2, recent.size)
        assertEquals(5L, recent.first().id)
    }

    @Test
    fun `recording triggers retention with the production limit`() = runTest {
        val dao = FakeHistoryDao()
        val repository = repository(dao)

        repository.record(TRACK, playedAt = 1L)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(HistoryRepository.RETENTION_LIMIT), dao.trimCalls)
    }

    @Test
    fun `retention keeps exactly the newest limit`() = runTest {
        val dao = FakeHistoryDao()
        val repository = repository(dao)
        repeat(5) { repository.record(TRACK, playedAt = it.toLong()) }

        repository.enforceRetention(limit = 2)

        assertEquals(2, dao.rows.size)
        assertEquals(listOf(5L, 4L), dao.rows.sortedByDescending { it.playedAt }.map { it.id })
    }

    @Test
    fun `retention never grows the log past the limit`() = runTest {
        val dao = FakeHistoryDao()
        val repository = repository(dao)
        // Pre-fill past the retention ceiling, as a long-lived install would.
        dao.seed(HistoryRepository.RETENTION_LIMIT + 25)

        repository.record(TRACK, playedAt = 1_000_000L)
        testScheduler.advanceUntilIdle()

        assertEquals(HistoryRepository.RETENTION_LIMIT, dao.rows.size)
        // The event just recorded is the newest row.
        assertEquals(1_000_000L, dao.newestFirst().first().playedAt)
    }

    @Test
    fun `the log is never empty for a repository that has recorded nothing`() = runTest {
        val dao = FakeHistoryDao()
        val repository = repository(dao)
        assertTrue(repository.observeRecent().first().isEmpty())
        assertEquals(0, repository.count())
    }

    private fun TestScope.repository(dao: HistoryDao) = HistoryRepository(
        historyDao = dao,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
    )

    private companion object {
        val TRACK = Track("t", "local", "Title", "Artist")
        val RICH_TRACK = Track(
            id = "rich",
            providerId = "itunes",
            title = "Around the World",
            artist = "Daft Punk",
            album = "Discovery",
            artworkUrl = "https://example.test/art.jpg",
            durationMs = 429_000L,
        )
    }
}

/** In-memory HistoryDao with the same append-only and ordering semantics. */
private class FakeHistoryDao : HistoryDao {

    val rows = mutableListOf<HistoryEntity>()
    val trimCalls = mutableListOf<Int>()
    private val state = MutableStateFlow<List<HistoryEntity>>(emptyList())
    private var nextId = 1L

    fun newestFirst(): List<HistoryEntity> = rows.sortedWith(
        compareByDescending<HistoryEntity> { it.playedAt }.thenByDescending { it.id },
    )

    suspend fun seed(count: Int) {
        repeat(count) { index ->
            insert(
                HistoryEntity(
                    providerId = "local",
                    trackId = "seed-$index",
                    title = "t",
                    artist = "a",
                    playedAt = index.toLong(),
                ),
            )
        }
    }

    override fun observeRecent(limit: Int): Flow<List<HistoryEntity>> =
        state.map { entries -> entries.take(limit) }

    override suspend fun insert(entry: HistoryEntity): Long {
        val id = nextId++
        rows += entry.copy(id = id)
        state.value = newestFirst()
        return id
    }

    override suspend fun deleteBeyondNewest(keep: Int) {
        trimCalls += keep
        val survivors = newestFirst().take(keep)
        rows.clear()
        rows += survivors.sortedBy { it.id }
        state.value = newestFirst()
    }

    override suspend fun count(): Int = rows.size
}
