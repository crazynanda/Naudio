package com.naudio.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the playback-history event log (M17).
 *
 * Shape: an append-only log of listening EVENTS, not a unique-track table. The
 * primary key is the auto-generated row id (a surrogate, because one track can
 * legitimately appear many times — once per qualifying listening session) and
 * the track identity is carried as the provider-aware pair
 * (`provider_id`, `track_id`) without a foreign key: a history row must
 * survive the track leaving the library, and a qualifying event is a snapshot,
 * not a reference.
 *
 * The metadata columns (`title`, `artist`, `album`, `artwork_url`,
 * `duration_ms`) are an intentional snapshot so Recently Played renders with
 * no provider lookup. Nothing here is ever updated or recomputed.
 *
 * No play counts, completion percentages, analytics or scrobbling columns.
 *
 * Mapping to the domain [com.naudio.core.model.History] is handled by
 * `HistoryMapper` in :data; Room types never leave :core:database.
 */
@Entity(
    tableName = "history",
    indices = [Index(value = ["played_at"])],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "provider_id")
    val providerId: String,

    @ColumnInfo(name = "track_id")
    val trackId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist")
    val artist: String,

    @ColumnInfo(name = "album")
    val album: String? = null,

    @ColumnInfo(name = "artwork_url")
    val artworkUrl: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    @ColumnInfo(name = "played_at")
    val playedAt: Long,
)
