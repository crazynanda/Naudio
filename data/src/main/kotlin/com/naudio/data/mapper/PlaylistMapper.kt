package com.naudio.data.mapper

import com.naudio.core.database.dao.PlaylistWithCount
import com.naudio.core.database.entity.PlaylistEntity
import com.naudio.core.model.Playlist

/** Internal mapper between playlist rows and the domain [Playlist]. */
object PlaylistMapper {

    fun toDomain(entity: PlaylistEntity, trackCount: Int): Playlist =
        Playlist(
            id = entity.id,
            name = entity.name,
            trackCount = trackCount,
        )

    /** Merges the DAO's LEFT JOIN count row into the domain model. */
    fun toDomain(row: PlaylistWithCount): Playlist = toDomain(row.playlist, row.trackCount)

    fun toEntity(playlist: Playlist, createdAt: Long): PlaylistEntity =
        PlaylistEntity(
            id = playlist.id,
            name = playlist.name,
            createdAt = createdAt,
        )
}
