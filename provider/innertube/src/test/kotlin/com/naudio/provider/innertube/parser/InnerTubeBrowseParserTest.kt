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
        assertEquals("https://lh3.googleusercontent.com/album-w544", album.artworkUrl)
        // This older response links no performer, so there is no artist to report:
        // its only subtitle is the year, which is what it is, not who made it.
        assertNull(album.artist)
        assertEquals("1989", album.year)
        // The songs shelf must not have absorbed the release cards.
        assertEquals(1, artist.tracks.size)
    }

    // ------------------------------------------------------------------
    // Artist, as the live response actually ships it
    //
    // artist_immersive.json reproduces the current shape end to end: an
    // `musicImmersiveHeaderRenderer` header, a bio in its own description block,
    // songs in a music shelf, and releases in CAROUSELs. These are the exact
    // three things a header-blind, shelf-only parser silently loses — the artist
    // rendered as "Unknown" with placeholder artwork, and no discography at all,
    // while the songs still loaded. Ids are the real ones YT Music publishes for
    // Oasis, not invented ones.
    // ------------------------------------------------------------------

    @Test
    fun `the artist name and artwork come from the immersive header`() {
        val artist = InnerTubeBrowseParser.parseArtist(
            "UCmMUZbaYdNH0bEd1PAlAqsA",
            Fixtures.load("artist_immersive.json"),
        )

        assertEquals("Oasis", artist.name)
        assertEquals("https://yt3.googleusercontent.com/oasis-w544-h544", artist.artworkUrl)
        // The id the caller asked for is echoed back, never one from the response.
        assertEquals("UCmMUZbaYdNH0bEd1PAlAqsA", artist.id)
    }

    @Test
    fun `the artist bio is read from its description block`() {
        val artist = InnerTubeBrowseParser.parseArtist(
            "UCmMUZbaYdNH0bEd1PAlAqsA",
            Fixtures.load("artist_immersive.json"),
        )

        assertEquals(
            "Oasis were an English rock band formed in Manchester in 1991.",
            artist.description,
        )
    }

    @Test
    fun `release carousels are mapped onto album summaries with their own fields`() {
        val artist = InnerTubeBrowseParser.parseArtist(
            "UCmMUZbaYdNH0bEd1PAlAqsA",
            Fixtures.load("artist_immersive.json"),
        )

        assertEquals(
            listOf("MPREb_9nqEki4ZDpp", "MPREb_4lE1N1bVd0O", "MPREb_7MPKLhibN5G"),
            artist.albums.map { it.id },
        )
        val album = artist.albums.first()
        assertEquals("(What's The Story) Morning Glory? (Remastered)", album.title)
        // The performer is the run the response LINKS; the leading "Album" run is
        // the release type and must not be reported as the artist.
        assertEquals("Oasis", album.artist)
        assertEquals("1995", album.year)
        assertEquals(
            "https://lh3.googleusercontent.com/morning-glory-w544-h544",
            album.artworkUrl,
        )
    }

    @Test
    fun `a related artist card is never reported as an album`() {
        // The "Related" carousel holds a channel id and the same two-row shape an
        // album has. Taking it would put a channel on the album destination.
        val artist = InnerTubeBrowseParser.parseArtist(
            "UCmMUZbaYdNH0bEd1PAlAqsA",
            Fixtures.load("artist_immersive.json"),
        )

        assertTrue(artist.albums.none { it.id == "UCt2KxZpY5D__kapeQ8cauQw" })
        assertTrue(artist.albums.none { it.title == "The Verve" })
    }

    @Test
    fun `the immersive page keeps its songs and cursor while gaining releases`() {
        val artist = InnerTubeBrowseParser.parseArtist(
            "UCmMUZbaYdNH0bEd1PAlAqsA",
            Fixtures.load("artist_immersive.json"),
        )

        assertEquals(
            listOf("ZrOKjDZOtkA", "3omcFPV38hQ"),
            artist.tracks.map { it.id },
        )
        assertEquals("ARTIST-PAGE-1", artist.continuation)
        // Release cards are not tracks, however they are laid out.
        assertEquals(2, artist.tracks.size)
    }

    @Test
    fun `an immersive header with no releases still reports the artist`() {
        val artist = InnerTubeBrowseParser.parseArtist(
            "UCmMUZbaYdNH0bEd1PAlAqsA",
            """
            {"header":{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"Oasis"}]}}},
             "contents":{}}
            """,
        )

        assertEquals("Oasis", artist.name)
        assertTrue(artist.albums.isEmpty())
        assertNull(artist.artworkUrl)
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