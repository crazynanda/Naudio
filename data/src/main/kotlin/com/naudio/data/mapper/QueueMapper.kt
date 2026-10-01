package com.naudio.data.mapper

import com.naudio.core.database.entity.QueueEntity
import com.naudio.core.model.Track

/** Internal mapper between queue rows and the domain [Track]. */
object QueueMapper {

    fun toDomain(entity: QueueEntity): Track =
        Track(
            id = entity.trackId,
            providerId = entity.providerId,
            title = entity.title,
            artist = entity.artist,
            album = entity.album,
            artworkUrl = entity.artworkUrl,
            durationMs = entity.durationMs,
        )

    /** Queue rows are a full metadata snapshot so restores need no join. */
    fun toEntity(orderIndex: Int, track: Track): QueueEntity =
        QueueEntity(
            orderIndex = orderIndex,
            providerId = track.providerId,
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            durationMs = track.durationMs,
            album = track.album,
            artworkUrl = track.artworkUrl,
        )
}
