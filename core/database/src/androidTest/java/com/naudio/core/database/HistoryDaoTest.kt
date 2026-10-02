package com.naudio.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.naudio.core.database.entity.HistoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M17 DAO coverage against real SQLite: the `history` event log must be
 * append-only, ordered newest first, bounded by the requested limit, and
 * trimmable to a retention ceiling. These are the SQL semantics the fake DAOs
 * in the unit tests can only approximate.
 */
@RunWith(AndroidJUnit4::class)
class HistoryDaoTest {

    private lateinit var db: NaudioDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NaudioDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insert_appendsAndAssignsIncreasingIds() = runTest {
        val first = db.historyDao().insert(entity(trackId = "a", playedAt = 1L))
        val second = db.historyDao().insert(entity(trackId = "a", playedAt = 2L))

        assertTrue(first > 0L)
        assertTrue(second > first)
        // Append-only: replaying one track adds a row, it never replaces one.
        assertEquals(2, db.historyDao().count())
    }

    @Test
    fun observeRecent_isNewestFirst() = runTest {
        db.historyDao().insert(entity(trackId = "old", playedAt = 100L))
        db.historyDao().insert(entity(trackId = "newest", playedAt = 300L))
        db.historyDao().insert(entity(trackId = "middle", playedAt = 200L))

        val rows = db.historyDao().observeRecent(10).first()
        assertEquals(listOf("newest", "middle", "old"), rows.map { it.trackId })
    }

    @Test
    fun observeRecent_tiesOnPlayedAtBreakOnId() = runTest {
        db.historyDao().insert(entity(trackId = "first", playedAt = 500L))
        db.historyDao().insert(entity(trackId = "second", playedAt = 500L))
        db.historyDao().insert(entity(trackId = "third", playedAt = 500L))

        val rows = db.historyDao().observeRecent(10).first()
        assertEquals(listOf("third", "second", "first"), rows.map { it.trackId })
    }

    @Test
    fun observeRecent_isEmptyOnAFreshInstall() = runTest {
        assertTrue(db.historyDao().observeRecent(10).first().isEmpty())
    }

    @Test
    fun observeRecent_honoursTheLimit() = runTest {
        repeat(5) { index -> db.historyDao().insert(entity(trackId = "t$index", playedAt = index.toLong())) }

        val rows = db.historyDao().observeRecent(2).first()
        assertEquals(2, rows.size)
        assertEquals("t4", rows.first().trackId)
    }

    @Test
    fun deleteBeyondNewest_keepsExactlyTheNewestRows() = runTest {
        repeat(5) { index -> db.historyDao().insert(entity(trackId = "t$index", playedAt = index.toLong())) }

        db.historyDao().deleteBeyondNewest(3)

        val rows = db.historyDao().observeRecent(10).first()
        assertEquals(3, rows.size)
        assertEquals(listOf("t4", "t3", "t2"), rows.map { it.trackId })
    }

    @Test
    fun deleteBeyondNewest_isANoOpBelowTheCeiling() = runTest {
        db.historyDao().insert(entity(trackId = "a", playedAt = 1L))
        db.historyDao().insert(entity(trackId = "b", playedAt = 2L))

        db.historyDao().deleteBeyondNewest(1000)

        assertEquals(2, db.historyDao().count())
    }

    @Test
    fun deleteBeyondNewest_preservesSnapshotMetadata() = runTest {
        db.historyDao().insert(entity(trackId = "older", playedAt = 1L))
        db.historyDao().insert(
            HistoryEntity(
                providerId = "itunes",
                trackId = "rich",
                title = "Around the World",
                artist = "Daft Punk",
                album = "Discovery",
                artworkUrl = "https://example.test/art.jpg",
                durationMs = 429_000L,
                playedAt = 2L,
            ),
        )

        // The trim keeps the NEWEST event, so the full snapshot must survive
        // intact: Recently Played renders from these columns alone.
        db.historyDao().deleteBeyondNewest(1)

        val kept = db.historyDao().observeRecent(10).first().single()
        assertEquals("rich", kept.trackId)
        assertEquals("itunes", kept.providerId)
        assertEquals("Around the World", kept.title)
        assertEquals("Daft Punk", kept.artist)
        assertEquals("Discovery", kept.album)
        assertEquals("https://example.test/art.jpg", kept.artworkUrl)
        assertEquals(429_000L, kept.durationMs)
    }

    private fun entity(trackId: String, playedAt: Long) = HistoryEntity(
        providerId = "local",
        trackId = trackId,
        title = "Title $trackId",
        artist = "Artist",
        album = null,
        artworkUrl = null,
        durationMs = 1_000L,
        playedAt = playedAt,
    )
}
