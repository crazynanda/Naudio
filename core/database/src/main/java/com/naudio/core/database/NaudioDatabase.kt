package com.naudio.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.naudio.core.database.dao.TrackDao
import com.naudio.core.database.entity.TrackEntity

/** Version 1 database. No migrations yet — schema is fresh and exported to source control. */
@Database(
    entities = [TrackEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class NaudioDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    companion object {
        private const val NAME = "naudio.db"

        fun open(context: Context): NaudioDatabase {
            return Room.databaseBuilder(context, NaudioDatabase::class.java, NAME)
                .build()
        }
    }
}
