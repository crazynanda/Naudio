package com.naudio.data.mapper

import com.naudio.core.database.entity.QueueEntity
import com.naudio.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M12: queue rows are a full metadata snapshot — album/artwork must map. */
class QueueMapperTest {

    private fun track() = Track(
        id = "t1",
        providerId = "itunes",
        title = "Around the World",
        artist = "Daft Punk",
        album = "Discovery",
        artworkUrl = "https://is1-ssl.mzstatic.com/600x600bb.jpg",
        durationMs = 7_289_000L,
    )

    @Test
    fun `toEntity snapshots album and artwork into the queue row`() {
        val entity = QueueMapper.toEntity(3, track())

        assertEquals(3, entity.orderIndex)
        assertEquals("t1", entity.trackId)
        assertEquals("itunes", entity.providerId)
        assertEquals("Around the World", entity.title)
        assertEquals("Daft Punk", entity.artist)
        assertEquals("Discovery", entity.album)
        assertEquals("https://is1-ssl.mzstatic.com/600x600bb.jpg", entity.artworkUrl)
        assertEquals(7_289_000L, entity.durationMs)
    }

    @Test
    fun `toDomain restores album and artwork from the queue row`() {
        val entity = QueueEntity(
            orderIndex = 0,
            providerId = "local",
            trackId = "42",
            title = "Tone",
            artist = "Unknown artist",
            durationMs = 3_000L,
            album = "Test Tones",
            artworkUrl = "content://media/external/audio/albumart/7",
        )

        val domain = QueueMapper.toDomain(entity)

        assertEquals("Test Tones", domain.album)
        assertEquals("content://media/external/audio/albumart/7", domain.artworkUrl)
    }

    @Test
    fun `null album and artwork stay null through the round-trip`() {
        val bare = Track(id = "x", providerId = "ytmusic", title = "T", artist = "A")

        val entity = QueueMapper.toEntity(0, bare)
        assertNull(entity.album)
        assertNull(entity.artworkUrl)

        val restored = QueueMapper.toDomain(entity)
        assertNull(restored.album)
        assertNull(restored.artworkUrl)
    }
}
