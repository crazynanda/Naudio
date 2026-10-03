package com.naudio.provider.innertube.parser

import com.naudio.provider.innertube.Fixtures
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Browse responses (home / artist / album / playlist) -> domain catalog values. */
class InnerTubeBrowseParserTest {

    // ------------------------------------------------------------------
    // Home
    // ------------------------------------------------------------------

    @Test
    fun `home keeps shelf order and section headings`() {
        val home = InnerTubeBrowseParser.parseHome(Fixtures.load("home.json"))

        assertEquals(2, home.shelves.size)
        assertEquals("Listen again", home.shelves[0].title)
        assertEquals("New releases", home.shelves[1].title)
        assertEquals("dQw4w9WgXcQ", home.shelves[0].tracks.single().id)
    }

    @Test
    fun `home flattens every shelf into one track list`() {
        val home = InnerTubeBrowseParser.parseHome(Fixtures.load("home.json"))

        assertEquals(listOf("dQw4w9WgXcQ", "yPYZpwSpKmA"), home.tracks.map { it.id })
    }

    @Test
    fun `a response with no shelves yields an empty home rather than failing`() {
        val home = InnerTubeBrowseParser.parseHome("""{"contents":{}}""")

        assertTrue(home.shelves.isEmpty())
        assertTrue(home.tracks.isEmpty())
    }

    // ------------------------------------------------------------------
    // Artist
    // ------------------------------------------------------------------

    @Test
    fun `artist reads the header, the songs shelf and the release cards`() {
        val artist = InnerTubeBrowseParser.parseArtist("UCrick", Fixtures.load("artist.json"))

        assertEquals("UCrick", artist.id)
        assertEquals("Rick Astley", artist.name)
        assertEquals("4.4M subscribers", artist.description)
        assertEquals("https://yt3.ggpht.com/artist=w544", artist.artworkUrl)
        assertEquals(listOf("dQw4w9WgXcQ"), artist.tracks.map { it.id })
        assertEquals("ARTIST-TOKEN-1", artist.continuation)
    }

    @Test
    fun `artist release cards become album summaries, never tracks`() {
        val artist = InnerTubeBrowseParser.parseArtist("UCrick", Fixtures.load("artist.json"))

        assertEquals(1, artist.albums.size)
        val album = artist.albums.single()
        assertEquals("MPREb_AlbumOne", album.id)
        assertEquals("Whenever You Need Somebody", album.title)
        assertEquals("1989", album.artist)
        assertEquals("https://lh3.googleusercontent.com/album-w544", album.artworkUrl)
        // The songs shelf must not have absorbed the release cards.
        assertEquals(1, artist.tracks.size)
    }

    @Test
    fun `a missing artist header degrades field by field`() {
        val artist = InnerTubeBrowseParser.parseArtist("UCx", """{"contents":{}}""")

        assertEquals("UCx", artist.id)
        assertEquals("Unknown", artist.name)
        assertNull(artist.description)
        assertTrue(artist.tracks.isEmpty())
    }

    // ------------------------------------------------------------------
    // Album
    // ------------------------------------------------------------------

    @Test
    fun `album reads header metadata and the ordered track list`() {
        val album = InnerTubeBrowseParser.parseAlbum("MPREb_AlbumOne", Fixtures.load("album.json"))

        assertEquals("MPREb_AlbumOne", album.id)
        assertEquals("Whenever You Need Somebody", album.title)
        assertEquals("Rick Astley", album.artist)
        assertEquals("1989", album.year)
        assertEquals("https://lh3.googleusercontent.com/album-cover-w544", album.artworkUrl)
        assertEquals(listOf("dQw4w9WgXcQ", "yPYZpwSpKmA"), album.tracks.map { it.id })
        assertNull(album.continuation)
    }

    @Test
    fun `an album with no release year reports none rather than guessing`() {
        val album = InnerTubeBrowseParser.parseAlbum("MPREb_X", Fixtures.load("album_no_year.json"))

        assertNull(album.year)
        assertEquals("Someone", album.artist)
    }

    // ------------------------------------------------------------------
    // Playlist
    // ------------------------------------------------------------------

    @Test
    fun `playlist reads author, count, tracks and cursor`() {
        val playlist = InnerTubeBrowseParser.parsePlaylist("VLPLabc", Fixtures.load("playlist.json"))

        assertEquals("VLPLabc", playlist.id)
        assertEquals("Road Trip Anthems", playlist.title)
        assertEquals("Naudio", playlist.author)
        assertEquals(42, playlist.trackCount)
        assertEquals(listOf("dQw4w9WgXcQ"), playlist.tracks.map { it.id })
        assertEquals("PLAYLIST-TOKEN-1", playlist.continuation)
    }

    @Test
    fun `a playlist without a count reports null rather than zero`() {
        val playlist = InnerTubeBrowseParser.parsePlaylist(
            "VLPLx",
            """{"header":{"musicDetailHeaderRenderer":{"title":{"text":"Untitled"}}}}""",
        )

        assertNull(playlist.trackCount)
        assertNull(playlist.author)
        assertTrue(playlist.tracks.isEmpty())
    }

    @Test
    fun `an empty body never throws for any browse surface`() {
        // Malformed JSON is a transport/decoding concern and throws; but a
        // well-formed body with nothing in it must degrade to empty values.
        assertEquals("Unknown", InnerTubeBrowseParser.parseArtist("UCx", "{}").name)
        assertEquals("Unknown", InnerTubeBrowseParser.parseAlbum("MPREb_x", "{}").title)
        assertEquals("Unknown", InnerTubeBrowseParser.parsePlaylist("VLPLx", "{}").title)
    }
}