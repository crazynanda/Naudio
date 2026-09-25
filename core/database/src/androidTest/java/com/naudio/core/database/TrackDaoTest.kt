package com.naudio.core.database

import android.content.Context
import androidx.room.Room
import com.naudio.core.database.dao.TrackDao
import com.naudio.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

/** In-memory Room database tests. Exercises the DAO surface only. */
class TrackDaoTest {

    private lateinit var dao: TrackDao
    private lateinit var db: com.naudio.core.database.NaudioDatabase

    @Before
    fun createDb() = runTest {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, com.naudio.core.database.NaudioDatabase::class.java)
            .build()
        dao = db.trackDao()
    }

    @After
    fun closeDb() = runTest {
        db.close()
    }

    private fun track(
        id: String = "123",
        providerId: String = "providerA",
        title: String = "Track",
        artist: String = "Artist",
        durationMs: Long = 10_000L,
        isFavorite: Boolean = false,
        savedAt: Long? = null,
    ) = TrackEntity(
        id = id,
        providerId = providerId,
        title = title,
        artist = artist,
        durationMs = durationMs,
        isFavorite = isFavorite,
        savedAt = savedAt,
    )

    @Test
    fun `observeFavorites_starts_empty`() = runTest {
        val list = dao.observeFavorites().first()
        assertTrue(list.isEmpty())

        // Inserting a non-favorite must not surface through observeFavorites.
        dao.upsertTrack(track())
        assertEquals(0, dao.observeFavorites().first().size)
    }

    @Test
    fun `favorite_sets_isFavorite_and_savedAt_unfavorite_drops_from_list`() = runTest {
        val now = Date().time

        // Favorite: savedAt=now, isFavorite=true
        dao.upsertTrack(track(isFavorite = true, savedAt = null))
        dao.updateFavorite("providerA", "123", true, now)
        val favorites = dao.observeFavorites().first()
        assertEquals(1, favorites.size)
        assertEquals("123", favorites[0].id)
        assertTrue(favorites[0].isFavorite)
        assertEquals(now, favorites[0].savedAt)

        // Unfavorite: isFavorite=false, savedAt=null -> drops out of favorites list
        dao.updateFavorite("providerA", "123", false, null)
        assertTrue(dao.observeFavorites().first().isEmpty())
    }

    @Test
    fun `composite_identity_lets_providerA_and_providerB_share_id_123`() = runTest {
        dao.upsertTrack(track(id = "123", providerId = "providerA", isFavorite = false))
        dao.upsertTrack(track(id = "123", providerId = "providerB", isFavorite = true, savedAt = Date().time))

        val favorites = dao.observeFavorites().first()
        assertEquals(1, favorites.size)
        assertEquals("providerB", favorites[0].providerId)
    }

    @Test
    fun `observeFavorites_returns_only_favorites_ordered_by_saved_at_descending`() = runTest {
        dao.upsertTrack(track(id = "a", isFavorite = true, savedAt = 100L))
        dao.upsertTrack(track(id = "b", isFavorite = true, savedAt = 300L))
        dao.upsertTrack(track(id = "c", isFavorite = false, savedAt = 200L)) // not favorite

        val favorites = dao.observeFavorites().first()
        assertEquals(2, favorites.size)
        assertEquals("b", favorites[0].id) // saved_at 300 first
        assertEquals("a", favorites[1].id)
    }

    @Test
    fun `upsertTrack_replaces_existing_row_keeping_identity_and_metadata`() = runTest {
        dao.upsertTrack(track(id = "123", providerId = "providerA", title = "old"))
        dao.upsertTrack(track(id = "123", providerId = "providerA", title = "new", isFavorite = true))

        val favorites = dao.observeFavorites().first()
        assertEquals(1, favorites.size)
        assertEquals("new", favorites[0].title)
        assertTrue(favorites[0].isFavorite)
    }
}
