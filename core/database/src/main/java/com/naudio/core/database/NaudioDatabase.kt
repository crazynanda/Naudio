package com.naudio.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.naudio.core.database.dao.QueueDao
import com.naudio.core.database.dao.TrackDao
import com.naudio.core.database.entity.QueueEntity
import com.naudio.core.database.entity.QueueStateEntity
import com.naudio.core.database.entity.TrackEntity

/**
 * Version 2 database (M10): adds the persistent playback queue
 * ([QueueEntity], [QueueStateEntity]) alongside the M3 favorites tables.
 * The v1→v2 migration creates the new tables without touching `tracks`,
 * so existing favorites survive the upgrade.
 */
@Database(
    entities = [TrackEntity::class, QueueEntity::class, QueueStateEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class NaudioDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    abstract fun queueDao(): QueueDao

    companion object {
        private const val NAME = "naudio.db"

        /**
         * Creates the M10 queue tables; favorites data is untouched.
         * Public so [androidx.room.testing.MigrationTestHelper]-based tests
         * can run the same migration the builder registers.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `queue_items` (
                        `order_index` INTEGER NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `track_id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `duration_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`order_index`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `queue_state` (
                        `id` INTEGER NOT NULL,
                        `current_index` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        fun open(context: Context): NaudioDatabase {
            return Room.databaseBuilder(context, NaudioDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
