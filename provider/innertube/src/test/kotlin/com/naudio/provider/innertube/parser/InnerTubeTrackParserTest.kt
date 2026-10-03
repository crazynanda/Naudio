package com.naudio.provider.innertube.parser

import com.naudio.provider.innertube.Fixtures
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Renderer -> domain [com.naudio.core.model.Track] mapping. */
class InnerTubeTrackParserTest {

    @Test
    fun `full renderer reads title, artist, album and duration from its columns`() {
        val tracks = InnerTubeTrackParser.parseAll(
            InnerTubeJson.parseObject(Fixtures.load("search_page.json")),
        )

        val track = tracks.first()
        assertEquals("Never Gonna Give You Up", track.title)
        assertEquals("Rick Astley", track.artist)
        assertEquals("Whenever You Need Somebody", track.album)
        assertEquals(213_000L, track.durationMs)
    }

    @Test
    fun `compact renderer splits its bullet separated runs`() {
        // watch.json's first rail item: "Together Forever • Rick Astley • 3:25".
        val related = InnerTubeWatchParser.parseRelated(Fixtures.load("watch.json"))

        val track = related.items.first()
        assertEquals("Together Forever", track.title)
        assertEquals("Rick Astley", track.artist)
        assertNull(track.album)
        assertEquals(205_000L, track.durationMs)
    }

    @Test
    fun `compact renderer keeps a real album out of the duration slot`() {
        // watch.json's second rail item: "Cry For Help • Rick Astley • Free • 4:22".
        val related = InnerTubeWatchParser.parseRelated(Fixtures.load("watch.json"))

        val track = related.items[1]
        assertEquals("Cry For Help", track.title)
        assertEquals("Rick Astley", track.artist)
        assertEquals("Free", track.album)
        assertEquals(262_000L, track.durationMs)
    }

    @Test
    fun `a renderer without a playable id maps to null`() {
        val track = InnerTubeTrackParser.parseItem(
            InnerTubeJson.parseObject(
                """
                {"musicResponsiveListItemRenderer":{
                  "flexColumns":[
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Some Artist"}]}}}
                  ],
                  "navigationEndpoint":{"browseEndpoint":{"browseId":"UCabc"}}
                }}
                """,
            ),
        )

        assertNull(track)
    }

    @Test
    fun `the watch endpoint id is used when playlistItemData is absent`() {
        val track = InnerTubeTrackParser.parseItem(
            InnerTubeJson.parseObject(
                """
                {"musicResponsiveListItemRenderer":{
                  "flexColumns":[
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"A Song"}]}}}
                  ],
                  "navigationEndpoint":{"watchEndpoint":{"videoId":"NAV-VIDEO-1"}}
                }}
                """,
            ),
        )

        assertEquals("NAV-VIDEO-1", track?.id)
        assertEquals("A Song", track?.title)
    }

    @Test
    fun `missing title and artist degrade to placeholders instead of blanks`() {
        val track = InnerTubeTrackParser.parseItem(
            InnerTubeJson.parseObject(
                """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"v1"}}}""",
            ),
        )

        assertEquals("Unknown title", track?.title)
        assertEquals("Unknown artist", track?.artist)
        assertEquals(0L, track?.durationMs)
    }

    @Test
    fun `every emitted track is stamped with the backend provider id`() {
        val tracks = InnerTubeTrackParser.parseAll(
            InnerTubeJson.parseObject(Fixtures.load("search_page.json")),
        )

        assertTrue(tracks.isNotEmpty())
        assertTrue(tracks.all { it.providerId == "ytmusic" })
    }

    @Test
    fun `both thumbnail shapes are understood`() {
        val nested = InnerTubeTrackParser.parseItem(
            InnerTubeJson.parseObject(
                """
                {"musicResponsiveListItemRenderer":{
                  "playlistItemData":{"videoId":"v1"},
                  "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                    {"url":"https://img/small"},
                    {"url":"https://img/large"}
                  ]}}}
                }}
                """,
            ),
        )
        val direct = InnerTubeTrackParser.parseItem(
            InnerTubeJson.parseObject(
                """
                {"musicResponsiveListItemRenderer":{
                  "playlistItemData":{"videoId":"v2"},
                  "thumbnail":{"musicThumbnailRenderer":{"thumbnail":[
                    {"url":"https://img/only"}
                  ]}}
                }}
                """,
            ),
        )

        assertEquals("https://img/large", nested?.artworkUrl)
        assertEquals("https://img/only", direct?.artworkUrl)
    }

    @Test
    fun `a nested renderer wrapper is unwrapped`() {
        val track = InnerTubeTrackParser.parseItem(
            InnerTubeJson.parseObject(
                """{"musicResponsiveListItemRendererWrapper":{"musicResponsiveListItemRenderer":{
                     "playlistItemData":{"videoId":"WRAPPED"},
                     "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"T"}]}}}]
                   }}}""",
            ),
        )

        assertEquals("WRAPPED", track?.id)
    }

    @Test
    fun `a compact renderer wrapping a full one is not emitted twice`() {
        val tracks = InnerTubeTrackParser.parseAll(
            InnerTubeJson.parseObject(
                """
                {"compactMusicResponsiveListItemRenderer":{
                  "musicResponsiveListItemRenderer":{
                    "playlistItemData":{"videoId":"ONCE"},
                    "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"T"}]}}}]
                  }
                }}
                """,
            ),
        )

        assertEquals(1, tracks.size)
        assertEquals("ONCE", tracks.single().id)
    }

    @Test
    fun `duration parsing handles hours and rejects junk`() {
        assertEquals(3_545_000L, InnerTubeJson.parseDurationMs("59:05"))
        assertEquals(3_723_000L, InnerTubeJson.parseDurationMs("1:02:03"))
        assertEquals(30_000L, InnerTubeJson.parseDurationMs("0:30"))
        assertNull(InnerTubeJson.parseDurationMs("abc"))
        assertNull(InnerTubeJson.parseDurationMs(""))
        assertNull(InnerTubeJson.parseDurationMs(null))
        assertNull(InnerTubeJson.parseDurationMs("3:xx"))
    }

    @Test
    fun `the collect walk is depth bounded`() {
        val deep = (1..40).fold("{}" as String) { acc, _ -> """{"wrap":$acc}""" }
        val root = InnerTubeJson.parseObject(deep)

        // A pathological depth must not blow up: the bound simply yields nothing.
        assertTrue(InnerTubeJson.collectByKey(root, "musicShelfRenderer").isEmpty())
    }
}