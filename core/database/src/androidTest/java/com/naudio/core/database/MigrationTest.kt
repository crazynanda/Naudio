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
 * Migrates a real schema-v1 database (favorites only) to v2 and verifies the
 * queue tables exist while favorites data survives — the exact path a
 * pre-M10 install takes on upgrade.
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
        // the data through the typed DAOs.
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NaudioDatabase::class.java,
            DB_NAME,
        ).build()

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

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
