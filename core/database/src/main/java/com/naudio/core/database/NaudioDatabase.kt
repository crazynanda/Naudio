package com.naudio.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.naudio.core.database.dao.HistoryDao
import com.naudio.core.database.dao.PlaylistDao
import com.naudio.core.database.dao.QueueDao
import com.naudio.core.database.dao.TrackDao
import com.naudio.core.database.entity.HistoryEntity
import com.naudio.core.database.entity.PlaylistEntity
import com.naudio.core.database.entity.PlaylistTrackCrossRef
import com.naudio.core.database.entity.QueueEntity
import com.naudio.core.database.entity.QueueStateEntity
import com.naudio.core.database.entity.TrackEntity

/**
 * Version 5 database (M17): adds the `history` table — the append-only
 * playback-history event log with its own metadata snapshot — while leaving
 * every existing table untouched. The v4→v5 migration is pure `CREATE TABLE`
 * plus `CREATE INDEX`, so favorites, artwork metadata, playlists, queue rows
 * and the queue position all survive untouched.
 */
@Database(
    entities = [
        TrackEntity::class,
        QueueEntity::class,
        QueueStateEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        HistoryEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class NaudioDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    abstract fun queueDao(): QueueDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun historyDao(): HistoryDao

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

        /**
         * M13: creates the user-playlist tables. `playlists` holds id/name/
         * createdAt; `playlist_tracks` holds ordered membership keyed by the
         * full composite track identity (provider_id, track_id) with a unique
         * per-playlist index (no duplicate occurrences) and composite foreign
         * keys into `playlists` and `tracks`. Track rows are never deleted
         * through these tables. The SQL mirrors exactly what Room generates
         * for the v4 entities so the migrated schema validates. Public so
         * [androidx.room.testing.MigrationTestHelper]-based tests can run the
         * same migration the builder registers.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlists` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_tracks` (
                        `playlist_id` INTEGER NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `track_id` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        PRIMARY KEY(`playlist_id`, `provider_id`, `track_id`),
                        FOREIGN KEY(`playlist_id`)
                            REFERENCES `playlists`(`id`)
                            ON UPDATE NO ACTION ON DELETE NO ACTION,
                        FOREIGN KEY(`provider_id`, `track_id`)
                            REFERENCES `tracks`(`provider_id`, `id`)
                            ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlist_id` " +
                        "ON `playlist_tracks` (`playlist_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_provider_id_track_id` " +
                        "ON `playlist_tracks` (`provider_id`, `track_id`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_playlist_tracks_playlist_id_provider_id_track_id` " +
                        "ON `playlist_tracks` (`playlist_id`, `provider_id`, `track_id`)",
                )
            }
        }

        /**
         * M17: creates the `history` event-log table. It is intentionally
         * foreign-key free: a listening event is a metadata snapshot that must
         * outlive the track row, and it carries no reference to delete or
         * cascade. The single index on `played_at` backs the newest-first
         * ordering used by the retention trim and by the Recently Played
         * queries. The SQL mirrors exactly what Room generates for the v5
         * entity so the migrated schema validates. Public so
         * [androidx.room.testing.MigrationTestHelper]-based tests can run the
         * same migration the builder registers.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `track_id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT,
                        `artwork_url` TEXT,
                        `duration_ms` INTEGER NOT NULL,
                        `played_at` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_history_played_at` " +
                        "ON `history` (`played_at`)",
                )
            }
        }

        fun open(context: Context): NaudioDatabase {
            return Room.databaseBuilder(context, NaudioDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
        }
    }
}
