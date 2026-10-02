package com.naudio.provider.lrclib

import com.naudio.core.model.Track
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.test.assertTrue

class LrclibMatchingTest {

    private val dummyClient = HttpClient(MockEngine { respondOk() })
    private val provider = LrclibLyricsProvider(dummyClient)

    @Test
    fun `fallback matching tests`() {
        val findBestMatchMethod = LrclibLyricsProvider::class.java.getDeclaredMethod(
            "findBestMatch",
            Track::class.java,
            List::class.java
        ).apply { isAccessible = true }

        val track = Track(
            id = "1",
            providerId = "local",
            title = "Test Title (Live)",
            artist = "Test Artist",
            durationMs = 200_000L
        )

        // Perfect match except title has (Live) which should be normalized away
        val goodResult = LrclibRecordDto(
            id = 1,
            trackName = "Test Title",
            artistName = "Test Artist",
            duration = 200,
            syncedLyrics = "[00:10.00] hello"
        )

        // Bad duration match (diff > 5s)
        val badDurationResult = LrclibRecordDto(
            id = 2,
            trackName = "Test Title",
            artistName = "Test Artist",
            duration = 210,
            syncedLyrics = "[00:10.00] hello"
        )

        val results = listOf(goodResult, badDurationResult)

        val match = findBestMatchMethod.invoke(provider, track, results) as LrclibRecordDto?

        assertNotNull(match)
        assertEquals(1L, match?.id)
    }

    @Test
    fun `normalization tests`() {
        val normalizeMethod = LrclibLyricsProvider::class.java.getDeclaredMethod(
            "normalize",
            String::class.java
        ).apply { isAccessible = true }

        val input = "Song Title (feat. Artist) [Remastered] - Live_Version"
        val normalized = normalizeMethod.invoke(provider, input) as String

        assertEquals("song title live version", normalized)
    }
}
