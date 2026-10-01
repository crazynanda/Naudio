package com.naudio.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Migration coverage:
 * - 1→2 (M10): queue tables are created while favorites data survives.
 * - 2→3 (M12): the `album` and `artwork_url` columns are added to `tracks` and
 *   `queue_items` via pure ALTER TABLE, so favorites, savedAt, queue rows and
 *   the persisted queue index all survive untouched.
 * - 3→4 (M13): the user-playlist tables are created while favorites, artwork
 *   metadata, queue rows and the queue position all survive untouched.
 */
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NaudioDatabase::class.java,
    )

    @Test
    fun migrate1To2_createsQueueTables_andPreservesFavorites() = runTest {
        // Create a v1 database and store a favorited track in it.
        val v1 = helper.createDatabase(DB_NAME, 1)
        v1.execSQL(
            """
            INSERT INTO tracks (id, provider_id, title, artist, duration_ms, is_favorite, saved_at)
            VALUES ('t1', 'local', 'Kept', 'Artist', 1000, 1, 42)
            """.trimIndent(),
        )
        v1.close()

        // Run MIGRATION_1_2; Room validates the resulting schema against the
        // exported v2 descriptor.
        val migrated = helper.runMigrationsAndValidate(DB_NAME, 2, true, NaudioDatabase.MIGRATION_1_2)
        assertTrue(migrated.isOpen)
        migrated.close()

        // Re-open the migrated file with the real database class and verify
        // the data through the typed DAOs. The 2→3 and 3→4 migrations are
        // registered so the open continues to the current (v4) schema without
        // recreating.
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NaudioDatabase::class.java,
            DB_NAME,
        )
            .addMigrations(NaudioDatabase.MIGRATION_2_3, NaudioDatabase.MIGRATION_3_4)
            .build()

        // Favorites data survived the migration.
        val favorites = db.trackDao().observeFavorites().first()
        assertEquals(1, favorites.size)
        assertEquals("t1", favorites[0].id)
        assertTrue(favorites[0].isFavorite)
        assertEquals(42L, favorites[0].savedAt)

        // The new queue tables are usable.
        db.queueDao().upsertState(com.naudio.core.database.entity.QueueStateEntity(currentIndex = 2))
        assertEquals(2, db.queueDao().observeState().first()?.currentIndex)
        assertTrue(db.queueDao().observeQueue().first().isEmpty())
        db.close()
    }

    @Test
    fun migrate2To3_addsArtworkColumns_andPreservesAllData() = runTest {
        // Build a realistic v2 database: a favorited track with savedAt, a
        // persisted two-item queue, and a queue position.
        val v2 = helper.createDatabase(DB_NAME_2_3, 2)
        v2.execSQL(
            """
            INSERT INTO tracks (id, provider_id, title, artist, duration_ms, is_favorite, saved_at)
            VALUES ('fav1', 'itunes', 'Around the World', 'Daft Punk', 7289000, 1, 1727600000000)
            """.trimIndent(),
        )
        v2.execSQL(
            """
            INSERT INTO tracks (id, provider_id, title, artist, duration_ms, is_favorite, saved_at)
            VALUES ('plain', 'local', 'Test Tone', 'Naudio', 3000, 0, NULL)
            """.trimIndent(),
        )
        v2.execSQL(
            """
            INSERT INTO queue_items (order_index, provider_id, track_id, title, artist, duration_ms)
            VALUES (0, 'itunes', 'fav1', 'Around the World', 'Daft Punk', 7289000)
            """.trimIndent(),
        )
        v2.execSQL(
            """
            INSERT INTO queue_items (order_index, provider_id, track_id, title, artist, duration_ms)
            VALUES (1, 'local', 'plain', 'Test Tone', 'Naudio', 3000)
            """.trimIndent(),
        )
        // The state row is a single-row holder keyed on id = 0 (the DAO reads
        // WHERE id = 0), carrying the persisted current index.
        v2.execSQL("INSERT INTO queue_state (id, current_index) VALUES (0, 1)")
        v2.close()

        // Run MIGRATION_2_3; Room validates the result against the exported
        // v3 descriptor (this already fails if a column is missing/mistyped).
        val migrated = helper.runMigrationsAndValidate(DB_NAME_2_3, 3, true, NaudioDatabase.MIGRATION_2_3)

        // The new columns exist and existing rows read back intact.
        migrated.query("SELECT * FROM tracks WHERE id = 'fav1' AND provider_id = 'itunes'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getColumnIndex("album") >= 0)
            assertTrue(cursor.getColumnIndex("artwork_url") >= 0)
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("is_favorite")))
            assertEquals(1727600000000L, cursor.getLong(cursor.getColumnIndexOrThrow("saved_at")))
            assertTrue(cursor.isNull(cursor.getColumnIndex("album")))
            assertTrue(cursor.isNull(cursor.getColumnIndex("artwork_url")))
        }
        migrated.close()

        // Re-open with the real database class and verify everything through
        // the typed DAOs. The file sits at v3 after the validated migration,
        // so the 3→4 migration is registered to finish the open at v4.
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NaudioDatabase::class.java,
            DB_NAME_2_3,
        )
            .addMigrations(NaudioDatabase.MIGRATION_3_4)
            .build()

        // Track rows survived: favorite state, savedAt, and metadata intact.
        val favorites = db.trackDao().observeFavorites().first()
        assertEquals(1, favorites.size)
        assertEquals("fav1", favorites[0].id)
        assertEquals("Around the World", favorites[0].title)
        assertTrue(favorites[0].isFavorite)
        assertEquals(1727600000000L, favorites[0].savedAt)
        assertEquals(null, favorites[0].album)

        // Queue rows and the current index survived.
        val queue = db.queueDao().observeQueue().first()
        assertEquals(2, queue.size)
        assertEquals("fav1", queue[0].trackId)
        assertEquals("plain", queue[1].trackId)
        assertEquals(1, db.queueDao().observeState().first()?.currentIndex)

        // The new columns are writable through the typed entities.
        db.trackDao().upsertTrack(
            com.naudio.core.database.entity.TrackEntity(
                id = "fav1",
                providerId = "itunes",
                title = "Around the World",
                artist = "Daft Punk",
                durationMs = 7_289_000L,
                album = "Discovery",
                artworkUrl = "https://is1-ssl.mzstatic.com/600x600bb.jpg",
                isFavorite = true,
                savedAt = 1727600000000L,
            ),
        )
        val refreshed = db.trackDao().byIds(listOf("itunes"), listOf("fav1")).single()
        assertEquals("Discovery", refreshed.album)
        assertEquals("https://is1-ssl.mzstatic.com/600x600bb.jpg", refreshed.artworkUrl)
        assertTrue(refreshed.isFavorite)
        db.close()
    }

    @Test
    fun migrate3To4_createsPlaylistTables_andPreservesAllData() = runTest {
        // Build a realistic v3 database: a favorited track carrying M12 artwork
        // metadata, a plain track, a persisted two-item queue with an artwork
        // snapshot, and a queue position.
        val v3 = helper.createDatabase(DB_NAME_3_4, 3)
        v3.execSQL(
            """
            INSERT INTO tracks (id, provider_id, title, artist, duration_ms, album, artwork_url, is_favorite, saved_at)
            VALUES ('fav1', 'itunes', 'Around the World', 'Daft Punk', 7289000, 'Discovery', 'https://is1-ssl.mzstatic.com/600x600bb.jpg', 1, 1727600000000)
            """.trimIndent(),
        )
        v3.execSQL(
            """
            INSERT INTO tracks (id, provider_id, title, artist, duration_ms, is_favorite, saved_at)
            VALUES ('plain', 'local', 'Test Tone', 'Naudio', 3000, 0, NULL)
            """.trimIndent(),
        )
        v3.execSQL(
            """
            INSERT INTO queue_items (order_index, provider_id, track_id, title, artist, duration_ms, album, artwork_url)
            VALUES (0, 'itunes', 'fav1', 'Around the World', 'Daft Punk', 7289000, 'Discovery', 'https://is1-ssl.mzstatic.com/600x600bb.jpg')
            """.trimIndent(),
        )
        v3.execSQL(
            """
            INSERT INTO queue_items (order_index, provider_id, track_id, title, artist, duration_ms)
            VALUES (1, 'local', 'plain', 'Test Tone', 'Naudio', 3000)
            """.trimIndent(),
        )
        v3.execSQL("INSERT INTO queue_state (id, current_index) VALUES (0, 1)")
        v3.close()

        // Run MIGRATION_3_4; Room validates the result against the exported
        // v4 descriptor (this already fails if a table/index is missing or
        // mistyped).
        val migrated = helper.runMigrationsAndValidate(DB_NAME_3_4, 4, true, NaudioDatabase.MIGRATION_3_4)

        // The new tables exist and start empty.
        migrated.query("SELECT COUNT(*) FROM playlists").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM playlist_tracks").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()

        // Re-open with the real database class and verify everything through
        // the typed DAOs.
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NaudioDatabase::class.java,
            DB_NAME_3_4,
        ).build()

        // Favorites survived, with M12 artwork metadata intact.
        val favorites = db.trackDao().observeFavorites().first()
        assertEquals(1, favorites.size)
        assertEquals("fav1", favorites[0].id)
        assertTrue(favorites[0].isFavorite)
        assertEquals(1727600000000L, favorites[0].savedAt)
        assertEquals("Discovery", favorites[0].album)
        assertEquals("https://is1-ssl.mzstatic.com/600x600bb.jpg", favorites[0].artworkUrl)

        // Queue state survived untouched.
        val queue = db.queueDao().observeQueue().first()
        assertEquals(2, queue.size)
        assertEquals("fav1", queue[0].trackId)
        assertEquals("plain", queue[1].trackId)
        assertEquals("Discovery", queue[0].album)
        assertEquals(1, db.queueDao().observeState().first()?.currentIndex)

        // The new playlist tables are usable: a playlist plus an ordered
        // membership row for the pre-existing track round-trips.
        val playlistId = db.playlistDao().insertPlaylist(
            com.naudio.core.database.entity.PlaylistEntity(name = "Migrated", createdAt = 1L),
        )
        db.playlistDao().appendTrack(playlistId, "itunes", "fav1")
        val tracks = db.playlistDao().observeTracks(playlistId).first()
        assertEquals(1, tracks.size)
        assertEquals("fav1", tracks[0].id)
        assertEquals("Discovery", tracks[0].album)
        assertEquals(1, db.playlistDao().playlistWithCount(playlistId)?.trackCount)
        db.close()
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
        const val DB_NAME_2_3 = "migration-test-2-3.db"
        const val DB_NAME_3_4 = "migration-test-3-4.db"
    }
}
