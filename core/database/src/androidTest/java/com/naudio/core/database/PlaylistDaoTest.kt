package com.naudio.core.database

import android.content.Context
import androidx.room.Room
import com.naudio.core.database.entity.PlaylistEntity
import com.naudio.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-memory Room tests for the user-playlist DAO (M13). */
class PlaylistDaoTest {

    private lateinit var dao: com.naudio.core.database.dao.PlaylistDao
    private lateinit var trackDao: com.naudio.core.database.dao.TrackDao
    private lateinit var db: NaudioDatabase

    @Before
    fun createDb() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NaudioDatabase::class.java).build()
        dao = db.playlistDao()
        trackDao = db.trackDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private suspend fun track(
        id: String,
        providerId: String = "local",
        title: String = "Track $id",
    ): TrackEntity {
        val entity = TrackEntity(
            id = id,
            providerId = providerId,
            title = title,
            artist = "Artist",
            durationMs = 1000L,
        )
        trackDao.upsertTrack(entity)
        return entity
    }

    private suspend fun playlist(name: String = "Playlist"): Long =
        dao.insertPlaylist(PlaylistEntity(name = name, createdAt = 1L))

    @Test
    fun createPlaylist_roundTripsName_andCreatedAt() = runTest {
        val id = dao.insertPlaylist(PlaylistEntity(name = "Road trip", createdAt = 12345L))
        assertTrue(id != 0L)

        val row = dao.playlistWithCount(id)
        assertNotNull(row)
        assertEquals("Road trip", row?.playlist?.name)
        assertEquals(12345L, row?.playlist?.createdAt)
        assertEquals(0, row?.trackCount)
    }

    @Test
    fun addTrack_returnsOrderedByPosition() = runTest {
        val playlistId = playlist()
        track("a")
        track("b")
        track("c")

        dao.appendTrack(playlistId, "local", "a")
        dao.appendTrack(playlistId, "local", "b")
        dao.appendTrack(playlistId, "local", "c")

        val tracks = dao.observeTracks(playlistId).first()
        assertEquals(listOf("a", "b", "c"), tracks.map { it.id })
    }

    @Test
    fun mixedProviderTracks_withSameId_areDistinctMembers() = runTest {
        val playlistId = playlist()
        track("x", providerId = "local")
        track("x", providerId = "itunes")

        dao.appendTrack(playlistId, "local", "x")
        dao.appendTrack(playlistId, "itunes", "x")

        val tracks = dao.observeTracks(playlistId).first()
        assertEquals(2, tracks.size)
        assertEquals(
            listOf("local" to "x", "itunes" to "x"),
            tracks.map { it.providerId to it.id },
        )
    }

    @Test
    fun duplicateTrackInsert_isIgnored_notMovedOrDuplicated() = runTest {
        val playlistId = playlist()
        track("a")
        track("b")
        dao.appendTrack(playlistId, "local", "a")
        dao.appendTrack(playlistId, "local", "b")

        // Re-insert a duplicate: a no-op returning -1, order stays intact.
        assertEquals(-1L, dao.appendTrack(playlistId, "local", "a"))

        val tracks = dao.observeTracks(playlistId).first()
        assertEquals(listOf("a", "b"), tracks.map { it.id })
    }

    @Test
    fun removeTrack_keepsOrder_andNeverDeletesTheTrackRow() = runTest {
        val playlistId = playlist()
        track("a")
        track("b")
        track("c")
        dao.appendTrack(playlistId, "local", "a")
        dao.appendTrack(playlistId, "local", "b")
        dao.appendTrack(playlistId, "local", "c")

        dao.deleteTrackRef(playlistId, "local", "b")

        // Remove the middle track: order preserved — a (pos 0), c (pos 2).
        assertEquals(
            listOf("a", "c"),
            dao.observeTracks(playlistId).first().map { it.id },
        )
        // The underlying track row is NOT deleted by playlist operations.
        assertNotNull(trackDao.byIds(listOf("local"), listOf("b")).firstOrNull())
    }

    @Test
    fun appendAfterRemoval_reusesTheFreedTailSlot_withoutColliding() = runTest {
        val playlistId = playlist()
        track("a")
        track("b")
        track("c")
        dao.appendTrack(playlistId, "local", "a") // pos 0
        dao.appendTrack(playlistId, "local", "b") // pos 1
        dao.appendTrack(playlistId, "local", "c") // pos 2

        // Remove the last track, then re-add it: the freed position is
        // reusable and no UNIQUE collision occurs.
        dao.deleteTrackRef(playlistId, "local", "c")
        dao.appendTrack(playlistId, "local", "c")

        assertEquals(
            listOf("a", "b", "c"),
            dao.observeTracks(playlistId).first().map { it.id },
        )
    }

    @Test
    fun deletePlaylist_removesItsRelationships_butNotTheTracks() = runTest {
        val playlistId = playlist("Doomed")
        track("a")
        dao.appendTrack(playlistId, "local", "a")

        dao.deletePlaylist(playlistId)

        assertNull(dao.playlistWithCount(playlistId))
        assertTrue(dao.observeTracks(playlistId).first().isEmpty())
        // The track itself survives (playlists never delete tracks).
        assertNotNull(trackDao.byIds(listOf("local"), listOf("a")).firstOrNull())
    }

    @Test
    fun observePlaylistsWithCount_countsPerPlaylist_includingEmpty() = runTest {
        val first = playlist("First")
        playlist("Second") // stays empty
        track("a")
        track("b")
        dao.appendTrack(first, "local", "a")
        dao.appendTrack(first, "local", "b")

        val rows = dao.observePlaylistsWithCount().first()
        assertEquals(2, rows.size)
        assertEquals(2, rows.first { it.playlist.id == first }.trackCount)
        assertEquals(0, rows.first { it.playlist.name == "Second" }.trackCount)
    }

    @Test
    fun renamePlaylist_updatesName_only() = runTest {
        val playlistId = playlist("Before")
        track("a")
        dao.appendTrack(playlistId, "local", "a")

        dao.updatePlaylist(
            PlaylistEntity(id = playlistId, name = "After", createdAt = 1L),
        )

        val row = dao.playlistWithCount(playlistId)
        assertEquals("After", row?.playlist?.name)
        assertEquals(1, row?.trackCount)
    }
}
