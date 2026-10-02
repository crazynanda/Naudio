package com.naudio.data.mapper

import com.naudio.core.database.entity.HistoryEntity
import com.naudio.core.model.History
import com.naudio.core.model.Track

/**
 * Internal mapper (M17): keeps Room types inside :core:database. [History] is
 * pure Kotlin, so :data is the only place that sees both sides.
 *
 * The round trip is lossless in both directions, including the id — which
 * matters because a history row is a distinct EVENT even when it snapshots a
 * track that was played many times before.
 */
object HistoryMapper {

    fun toDomain(entity: HistoryEntity): History = History(
        id = entity.id,
        providerId = entity.providerId,
        trackId = entity.trackId,
        title = entity.title,
        artist = entity.artist,
        album = entity.album,
        artworkUrl = entity.artworkUrl,
        durationMs = entity.durationMs,
        playedAt = entity.playedAt,
    )

    /**
     * Snapshots [track] into an insertable entity. The id is left at 0 so Room
     * auto-generates it, and [playedAt] is passed in rather than read from the
     * system clock here — the caller owns time so tests stay deterministic.
     */
    fun toEntity(track: Track, playedAt: Long): HistoryEntity = HistoryEntity(
        providerId = track.providerId,
        trackId = track.id,
        title = track.title,
        artist = track.artist,
        album = track.album,
        artworkUrl = track.artworkUrl,
        durationMs = track.durationMs,
        playedAt = playedAt,
    )
}
