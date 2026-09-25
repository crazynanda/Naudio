package com.naudio.data.mapper

import com.naudio.core.database.entity.TrackEntity
import com.naudio.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackMapperTest {

    @Test
    fun `toDomain maps all fields correctly`() {
        val entity = TrackEntity(
            id = "t1",
            providerId = "local",
            title = "Title",
            artist = "Artist",
            durationMs = 5_000L,
            isFavorite = true,
            savedAt = 12345L,
        )

        val domain = TrackMapper.toDomain(entity)

        assertEquals("t1", domain.id)
        assertEquals("local", domain.providerId)
        assertEquals("Title", domain.title)
        assertEquals("Artist", domain.artist)
        assertEquals(5_000L, domain.durationMs)
    }

    @Test
    fun `toEntity maps all fields correctly`() {
        val track = Track(
            id = "t2",
            providerId = "default",
            title = "Title2",
            artist = "Artist2",
            durationMs = 3_000L,
        )

        val entity = TrackMapper.toEntity(track)

        assertEquals("t2", entity.id)
        assertEquals("default", entity.providerId)
        assertEquals("Title2", entity.title)
        assertEquals("Artist2", entity.artist)
        assertEquals(3_000L, entity.durationMs)
        assertEquals(false, entity.isFavorite)
        assertEquals(null, entity.savedAt)
    }

    @Test
    fun `toEntity defaults isFavorite=false and savedAt=null`() {
        val track = Track("id", "provider", "title", "artist")
        val entity = TrackMapper.toEntity(track)

        assertEquals(false, entity.isFavorite)
        assertEquals(null, entity.savedAt)
    }

    @Test
    fun `round-trip preserves providerId and metadata`() {
        val original = Track(
            id = "track-1",
            providerId = "remote",
            title = "The Track",
            artist = "The Artist",
            durationMs = 123_456L,
        )

        val roundTripped = TrackMapper.toDomain(TrackMapper.toEntity(original))

        assertEquals(original.id, roundTripped.id)
        assertEquals(original.providerId, roundTripped.providerId)
        assertEquals(original.title, roundTripped.title)
        assertEquals(original.artist, roundTripped.artist)
        assertEquals(original.durationMs, roundTripped.durationMs)
    }
}
