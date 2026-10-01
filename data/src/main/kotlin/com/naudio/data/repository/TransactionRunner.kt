package com.naudio.data.repository

import androidx.room.withTransaction
import com.naudio.core.database.NaudioDatabase

/**
 * Executes [block] inside a single database transaction (M13).
 *
 * Why this exists: the add-to-playlist persistence rule — "upsert the track,
 * then insert the playlist-track relationship, transactionally" — spans two
 * DAOs (TrackDao + PlaylistDao), so the transaction must run at
 * `RoomDatabase` level. [withTransaction] (room-ktx's suspend
 * `runInTransaction` extension) is the Room-sanctioned way to do that without
 * blocking-dispatcher juggling.
 *
 * This abstraction keeps the repository free of a direct `RoomDatabase`
 * dependency in its constructor and gives unit tests a one-line fake that
 * runs the block immediately (sequentially), matching the real
 * implementation's atomicity semantics closely enough for the
 * persistence-order assertions.
 */
class TransactionRunner(private val withTransaction: suspend (suspend () -> Unit) -> Unit) {

    /** Runs [block] atomically: either every write lands, or none does. */
    suspend fun <T> inTransaction(block: suspend () -> T): T {
        var result: T? = null
        withTransaction { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    companion object {
        /** The production runner, bound to the real Room database. */
        fun forDatabase(database: NaudioDatabase): TransactionRunner = TransactionRunner { block ->
            database.withTransaction { block() }
        }
    }
}
