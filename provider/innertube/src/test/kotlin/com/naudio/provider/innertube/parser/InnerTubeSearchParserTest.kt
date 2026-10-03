package com.naudio.provider.innertube.parser

import com.naudio.provider.api.PageToken
import com.naudio.provider.innertube.Fixtures
import kotlinx.serialization.SerializationException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Search response -> one page of domain tracks plus an opaque cursor. */
class InnerTubeSearchParserTest {

    @Test
    fun `maps tracks, artwork and duration from a first page`() {
        val page = InnerTubeSearchParser.parse(Fixtures.load("search_page.json"))

        assertEquals(2, page.items.size)
        val first = page.items[0]
        assertEquals("dQw4w9WgXcQ", first.id)
        assertEquals("ytmusic", first.providerId)
        assertEquals("Never Gonna Give You Up", first.title)
        assertEquals("Rick Astley", first.artist)
        assertEquals("Whenever You Need Somebody", first.album)
        assertEquals("https://lh3.googleusercontent.com/cover-w544-h544", first.artworkUrl)
        assertEquals(213_000L, first.durationMs)

        val second = page.items[1]
        assertEquals("yPYZpwSpKmA", second.id)
        assertEquals("Together Forever", second.title)
        assertEquals(205_000L, second.durationMs)
    }

    @Test
    fun `a non-track renderer is dropped rather than reported as a song`() {
        // The fixture's third item is an artist row: a browse endpoint, no video id.
        val page = InnerTubeSearchParser.parse(Fixtures.load("search_page.json"))

        assertTrue(page.items.none { it.id.isBlank() })
        assertEquals(2, page.items.size)
    }

    @Test
    fun `extracts the opaque continuation`() {
        val page = InnerTubeSearchParser.parse(Fixtures.load("search_page.json"))

        assertEquals(PageToken.Opaque("CONT-TOKEN-1"), page.nextToken)
    }

    @Test
    fun `the last page carries no continuation`() {
        val page = InnerTubeSearchParser.parse(Fixtures.load("search_page_2.json"))

        assertEquals(1, page.items.size)
        assertEquals("zx3Mc9Zq4PA", page.items.single().id)
        assertNull(page.nextToken)
    }

    @Test
    fun `zero results is an empty page, not a failure`() {
        val page = InnerTubeSearchParser.parse(Fixtures.load("search_empty.json"))

        assertTrue(page.items.isEmpty())
        assertNull(page.nextToken)
    }

    @Test
    fun `an unrecognised container fails loudly instead of silently emptying`() {
        val ex = assertFailsWith<SerializationException> {
            InnerTubeSearchParser.parse("""{"contents":{"someFutureRenderer":{"changed":true}}}""")
        }
        assertTrue(ex.message!!.contains("search results container"))
    }

    @Test
    fun `a non-object body fails as malformed`() {
        assertFailsWith<SerializationException> { InnerTubeSearchParser.parse("""["not","an","object"]""") }
    }

    @Test
    fun `a body missing contents fails as malformed`() {
        assertFailsWith<SerializationException> { InnerTubeSearchParser.parse("""{"responseContext":{}}""") }
    }

    @Test
    fun `invalid json fails as malformed`() {
        assertFailsWith<SerializationException> { InnerTubeSearchParser.parse("{not json") }
    }
}