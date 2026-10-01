package com.naudio.provider.local

import com.naudio.core.model.Track
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaStoreTrackMapperTest {

    @Test
    fun `maps a fully populated MediaStore row`() {
        val row = MediaStoreTrackRow(
            id = 42L,
            title = "Song A",
            artist = "Artist A",
            album = "Album A",
            durationMs = 185_000L,
        )

        val track = row.toTrack()

        assertEquals("42", track.id)
        assertEquals("local", track.providerId)
        assertEquals("Song A", track.title)
        assertEquals("Artist A", track.artist)
        assertEquals("Album A", track.album)
        assertEquals(185_000L, track.durationMs)
    }

    @Test
    fun `provider id is the stable local identifier`() {
        assertEquals("local", LocalProviderIds.LOCAL)
        assertEquals("local", MediaStoreTrackRow(1, "t", "a", "al", 1L).toTrack().providerId)
        assertEquals("Local Device", LocalProviderIds.DISPLAY_NAME)
    }

    @Test
    fun `null or blank title and artist fall back to Unknown placeholders`() {
        val track = MediaStoreTrackRow(id = 7L, title = null, artist = "  ", album = null, durationMs = 0L).toTrack()

        assertEquals("Unknown title", track.title)
        assertEquals("Unknown artist", track.artist)
    }

    @Test
    fun `blank album maps to null instead of placeholder`() {
        val track = MediaStoreTrackRow(id = 7L, title = "t", artist = "a", album = "  ", durationMs = 1L).toTrack()

        assertNull(track.album)
    }

    @Test
    fun `non-positive duration maps to zero`() {
        val track = MediaStoreTrackRow(id = 7L, title = "t", artist = "a", album = null, durationMs = -5L).toTrack()

        assertEquals(0L, track.durationMs)
    }

    @Test
    fun `content uri follows MediaStore audio media pattern`() {
        assertEquals("content://media/external/audio/media/42", trackContentUri(42L))
    }

    // ------------------------------------------------------------------
    // M12: album-art URI
    // ------------------------------------------------------------------

    @Test
    fun `row artwork url maps onto the track`() {
        val track = MediaStoreTrackRow(
            id = 7L,
            title = "t",
            artist = "a",
            album = "al",
            durationMs = 1L,
            artworkUrl = "content://media/external/audio/albumart/17",
        ).toTrack()

        assertEquals("content://media/external/audio/albumart/17", track.artworkUrl)
    }

    @Test
    fun `missing album artwork maps to null artworkUrl`() {
        val track = MediaStoreTrackRow(
            id = 7L,
            title = "t",
            artist = "a",
            album = null,
            durationMs = 1L,
            artworkUrl = null,
        ).toTrack()

        assertNull(track.artworkUrl)
    }
}
