package com.naudio.core.database

import android.content.Context
import androidx.room.Room
import com.naudio.core.database.dao.QueueDao
import com.naudio.core.database.entity.QueueEntity
import com.naudio.core.database.entity.QueueStateEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-memory Room tests for the persistent queue DAO (M10). */
class QueueDaoTest {

    private lateinit var dao: QueueDao
    private lateinit var db: NaudioDatabase

    @Before
    fun createDb() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NaudioDatabase::class.java).build()
        dao = db.queueDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun item(index: Int, providerId: String = "local", trackId: String = "t$index") =
        QueueEntity(
            orderIndex = index,
            providerId = providerId,
            trackId = trackId,
            title = "Track $index",
            artist = "Artist",
            durationMs = 1000L + index,
        )

    @Test
    fun observeQueue_startsEmpty_andReturnsOrderedByOrderIndex() = runTest {
        assertTrue(dao.observeQueue().first().isEmpty())

        dao.insertAll(listOf(item(1), item(0), item(2)))
        val queue = dao.observeQueue().first()
        assertEquals(listOf(0, 1, 2), queue.map { it.orderIndex })
    }

    @Test
    fun clearQueue_removesAllItems() = runTest {
        dao.insertAll(listOf(item(0), item(1)))
        dao.clearQueue()
        assertTrue(dao.observeQueue().first().isEmpty())
    }

    @Test
    fun insertAll_replacesRowsSharingAnOrderIndex() = runTest {
        dao.insertAll(listOf(item(0, providerId = "local", trackId = "old")))
        dao.insertAll(listOf(item(0, providerId = "itunes", trackId = "new")))

        val queue = dao.observeQueue().first()
        assertEquals(1, queue.size)
        assertEquals("itunes", queue[0].providerId)
        assertEquals("new", queue[0].trackId)
    }

    @Test
    fun state_roundTripsCurrentIndex_andNull() = runTest {
        assertNull(dao.observeState().first())

        dao.upsertState(QueueStateEntity(currentIndex = 3))
        assertEquals(3, dao.observeState().first()?.currentIndex)

        dao.upsertState(QueueStateEntity(currentIndex = null))
        assertNull(dao.observeState().first()?.currentIndex)
    }

    @Test
    fun compositeProviderIdentity_isStoredVerbatim() = runTest {
        dao.insertAll(
            listOf(
                item(0, providerId = "itunes", trackId = "1440847780"),
                item(1, providerId = "local", trackId = "1440847780"),
            ),
        )
        val queue = dao.observeQueue().first()
        assertEquals(2, queue.size)
        assertEquals("itunes" to "1440847780", queue[0].providerId to queue[0].trackId)
        assertEquals("local" to "1440847780", queue[1].providerId to queue[1].trackId)
    }
}
