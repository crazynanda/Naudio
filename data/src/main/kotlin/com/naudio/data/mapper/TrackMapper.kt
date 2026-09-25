package com.naudio.data.mapper

import com.naudio.core.database.entity.TrackEntity
import com.naudio.core.model.Track

/** Internal mapper: Keep Room types inside :core:database; the domain model is
 * reachable from here without any dependency on Room.
 */
object TrackMapper {

    fun toDomain(entity: TrackEntity): Track =
        Track(
            id = entity.id,
            providerId = entity.providerId,
            title = entity.title,
            artist = entity.artist,
            durationMs = entity.durationMs,
        )

    fun toEntity(track: Track): TrackEntity =
        TrackEntity(
            id = track.id,
            providerId = track.providerId,
            title = track.title,
            artist = track.artist,
            durationMs = track.durationMs,
        )
}
