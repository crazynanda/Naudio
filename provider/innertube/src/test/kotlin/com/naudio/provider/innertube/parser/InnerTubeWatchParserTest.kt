package com.naudio.provider.innertube.parser

import com.naudio.provider.api.PageToken
import com.naudio.provider.innertube.Fixtures
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Watch page -> single-song metadata and the related rail. */
class InnerTubeWatchParserTest {

    @Test
    fun `song metadata comes from videoDetails`() {
        val track = InnerTubeWatchParser.parseSong(Fixtures.load("watch.json"))

        assertEquals("dQw4w9WgXcQ", track?.id)
        assertEquals("ytmusic", track?.providerId)
        assertEquals("Never Gonna Give You Up", track?.title)
        assertEquals("Rick Astley", track?.artist)
        assertEquals(213_000L, track?.durationMs)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", track?.artworkUrl)
    }

    @Test
    fun `a response without videoDetails is not found rather than an error`() {
        assertNull(InnerTubeWatchParser.parseSong("""{"contents":{}}"""))
        assertNull(InnerTubeWatchParser.parseSong("{}"))
    }

    @Test
    fun `a song with no thumbnail reports none`() {
        val track = InnerTubeWatchParser.parseSong(
            """{"videoDetails":{"videoId":"v1","title":"T","author":"A","lengthSeconds":"10"}}""",
        )

        assertEquals("T", track?.title)
        assertNull(track?.artworkUrl)
        assertEquals(10_000L, track?.durationMs)
    }

    @Test
    fun `a non-numeric length yields a zero duration rather than throwing`() {
        val track = InnerTubeWatchParser.parseSong(
            """{"videoDetails":{"videoId":"v1","title":"T","author":"A","lengthSeconds":"N/A"}}""",
        )

        assertEquals(0L, track?.durationMs)
    }

    @Test
    fun `related rail is mapped and carries its own cursor`() {
        val page = InnerTubeWatchParser.parseRelated(Fixtures.load("watch.json"))

        assertEquals(listOf("yPYZpwSpKmA", "zx3Mc9Zq4PA"), page.items.map { it.id })
        assertEquals(PageToken.Opaque("RELATED-TOKEN-1"), page.nextToken)
    }

    @Test
    fun `a watch page with no related rail is an empty page, not a failure`() {
        val page = InnerTubeWatchParser.parseRelated(
            """{"videoDetails":{"videoId":"v1","title":"T","author":"A","lengthSeconds":"10"}}""",
        )

        assertTrue(page.items.isEmpty())
        assertNull(page.nextToken)
    }

    @Test
    fun `an empty related rail is an empty page`() {
        val page = InnerTubeWatchParser.parseRelated(
            """{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"related":{"endItems":[]}}}}""",
        )

        assertTrue(page.items.isEmpty())
        assertNull(page.nextToken)
    }

    @Test
    fun `the watch parser never reads stream data`() {
        // The watch page can carry player-shaped blocks; they must be ignored.
        val page = InnerTubeWatchParser.parseRelated(
            """
            {"streamingData":{"adaptiveFormats":[{"url":"https://stream.example/a","mimeType":"audio/mp4"}]},
             "contents":{"singleColumnMusicWatchNextResultsRenderer":{"related":{"endItems":[]}}}}
            """,
        )

        assertTrue(page.items.isEmpty())
    }
}