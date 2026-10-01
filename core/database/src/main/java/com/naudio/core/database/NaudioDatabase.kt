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
 * Version 3 database (M12): adds the `album` and `artwork_url` metadata
 * columns to `tracks` and `queue_items`. The v2→v3 migration is pure
 * `ALTER TABLE ... ADD COLUMN`, so every existing row (favorites, savedAt,
 * queue items, queue position) survives untouched with the new columns null.
 */
@Database(
    entities = [TrackEntity::class, QueueEntity::class, QueueStateEntity::class],
    version = 3,
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

        /**
         * M12: adds the nullable `album` and `artwork_url` TEXT columns to
         * both `tracks` and `queue_items`. Existing rows keep every value;
         * the new columns start as null. Public so
         * [androidx.room.testing.MigrationTestHelper]-based tests can run the
         * same migration the builder registers.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `album` TEXT")
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `artwork_url` TEXT")
                db.execSQL("ALTER TABLE `queue_items` ADD COLUMN `album` TEXT")
                db.execSQL("ALTER TABLE `queue_items` ADD COLUMN `artwork_url` TEXT")
            }
        }

        fun open(context: Context): NaudioDatabase {
            return Room.databaseBuilder(context, NaudioDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }
    }
}
