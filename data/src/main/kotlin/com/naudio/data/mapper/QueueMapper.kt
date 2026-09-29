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
            durationMs = entity.durationMs,
        )
}
