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

    // ------------------------------------------------------------------
    // Catalog ids: the provider's own identity for the artist and album
    //
    // The live songs-filtered search response hangs each entity's
    // `navigationEndpoint.browseEndpoint.browseId` on the very run holding that
    // entity's name. The ids used below are the ones YT Music itself published in
    // its public `search("wonderwall", "songs")` example output (artist
    // UCmMUZbaYdNH0bEd1PAlAqsA, release MPREb_9nqEki4ZDpp), reproduced here so
    // the mapping is asserted against the service's real shape rather than
    // against an invented one.
    // ------------------------------------------------------------------

    @Test
    fun `the artist and album ids are preserved alongside their display strings`() {
        val tracks = InnerTubeTrackParser.parseAll(
            InnerTubeJson.parseObject(Fixtures.load("search_page_catalog_ids.json")),
        )

        val track = tracks.first { it.id == "ZrOKjDZOtkA" }
        assertEquals("Oasis", track.artist)
        assertEquals("UCmMUZbaYdNH0bEd1PAlAqsA", track.artistId)
        assertEquals("(What's The Story) Morning Glory? (Remastered)", track.album)
        assertEquals("MPREb_9nqEki4ZDpp", track.albumId)
    }

    @Test
    fun `an artist id is kept when the row packs artist, album and duration into one column`() {
        // The web client also ships artist • album • duration as a single column.
        // The artist id must survive that shape; the album id must NOT appear for
        // an album this mapper does not surface a title for, so no label could
        // ever claim a release the user was never shown.
        val tracks = InnerTubeTrackParser.parseAll(
            InnerTubeJson.parseObject(Fixtures.load("search_page_catalog_ids.json")),
        )

        val track = tracks.first { it.id == "VXb8P0qMSKo" }
        assertEquals("Oasis", track.artist)
        assertEquals("UCmMUZbaYdNH0bEd1PAlAqsA", track.artistId)
        assertNull(track.album)
        assertNull(track.albumId)
    }

    @Test
    fun `a row the provider links nowhere reports no catalog ids`() {
        val tracks = InnerTubeTrackParser.parseAll(
            InnerTubeJson.parseObject(Fixtures.load("search_page_catalog_ids.json")),
        )

        val unlinked = tracks.first { it.id == "kN0BpM9tRcU" }
        assertEquals("Daft Punk", unlinked.artist)
        assertEquals("Discovery", unlinked.album)
        assertNull(unlinked.artistId)
        assertNull(unlinked.albumId)
    }

    @Test
    fun `a response with no endpoints at all still maps with no ids`() {
        // search_page.json predates id-bearing runs entirely: the mapping must not
        // require them, and must not invent one for a row the provider left plain.
        val tracks = InnerTubeTrackParser.parseAll(
            InnerTubeJson.parseObject(Fixtures.load("search_page.json")),
        )

        assertTrue(tracks.isNotEmpty())
        assertTrue(tracks.all { it.artistId == null && it.albumId == null })
    }

    @Test
    fun `a compact rail splits the artist and album ids by their own runs`() {
        val track = InnerTubeTrackParser.parseItem(
            InnerTubeJson.parseObject(
                """
                {"compactMusicResponsiveListItemRenderer":{
                  "playlistItemData":{"videoId":"COMPACT-1"},
                  "text":{"runs":[
                    {"text":"Wonderwall"},
                    {"text":" • "},
                    {"text":"Oasis","navigationEndpoint":{"browseEndpoint":{"browseId":"UCmMUZbaYdNH0bEd1PAlAqsA"}}},
                    {"text":" • "},
                    {"text":"Morning Glory","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_9nqEki4ZDpp"}}},
                    {"text":" • "},
                    {"text":"4:19"}
                  ]}
                }}
                """,
            ),
        )

        assertEquals("UCmMUZbaYdNH0bEd1PAlAqsA", track?.artistId)
        assertEquals("MPREb_9nqEki4ZDpp", track?.albumId)
    }

    @Test
    fun `a release id is never reported as the artist when the two names match`() {
        // Same-name collision: the release is linked, the performer is not. An
        // artist destination handed MPRE… would ask the provider for a channel it
        // never named, so the artist id stays null instead.
        val track = InnerTubeTrackParser.parseItem(
            InnerTubeJson.parseObject(
                """
                {"musicResponsiveListItemRenderer":{
                  "playlistItemData":{"videoId":"SAME-NAME"},
                  "flexColumns":[
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}},
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Oasis"}]}}},
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                      {"text":"Oasis","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_9nqEki4ZDpp"}}}
                    ]}}},
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"3:00"}]}}}
                  ]
                }}
                """,
            ),
        )

        assertEquals("Oasis", track?.artist)
        assertNull(track?.artistId)
        assertEquals("MPREb_9nqEki4ZDpp", track?.albumId)
    }

    @Test
    fun `a blank browse id is treated as no id at all`() {
        val track = InnerTubeTrackParser.parseItem(
            InnerTubeJson.parseObject(
                """
                {"musicResponsiveListItemRenderer":{
                  "playlistItemData":{"videoId":"BLANK-LINK"},
                  "flexColumns":[
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}},
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                      {"text":"Oasis","navigationEndpoint":{"browseEndpoint":{"browseId":"  "}}}
                    ]}}}
                  ]
                }}
                """,
            ),
        )

        assertNull(track?.artistId)
    }

    @Test
    fun `the collect walk is depth bounded`() {
        val deep = (1..40).fold("{}" as String) { acc, _ -> """{"wrap":$acc}""" }
        val root = InnerTubeJson.parseObject(deep)

        // A pathological depth must not blow up: the bound simply yields nothing.
        assertTrue(InnerTubeJson.collectByKey(root, "musicShelfRenderer").isEmpty())
    }
}