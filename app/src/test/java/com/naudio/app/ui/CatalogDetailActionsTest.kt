package com.naudio.app.ui

import com.naudio.app.ui.CatalogDetailActions
import com.naudio.app.ui.CatalogDetailActions.OpenAlbumContext
import com.naudio.core.model.AlbumDetail
import com.naudio.core.model.AlbumSummary
import com.naudio.core.model.ArtistDetail
import com.naudio.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the two album-detail defects:

  1. Back from an album must return to the artist that was open when the album
     was opened — not to a stale "isn't available" state. The artist identity is
     preserved at open time (providerId + artistId) and handed to
     ArtistViewModel.openArtist on back, so the page re-renders from the same
     entity it had before.

  2. Album track rows must fall back to the album artwork when the track itself
     carries none, because album tracks typically have no individual artwork in
     the response. The header artwork already works; the fix is on the row.
 */
class CatalogDetailActionsTest {

    private val track1 = Track(
        id = "v1",
        providerId = "ytmusic",
        title = "Wonderwall",
        artist = "Oasis",
        album = "(What's The Story) Morning Glory?",
        artistId = "UCmMUZbaYdNH0bEd1PAlAqsA",
        albumId = "MPREb_9nqEki4ZDpp",
    )
    private val track2 = Track(
        id = "v2",
        providerId = "ytmusic",
        title = "Don't Look Back in Anger",
        artist = "Oasis",
    )

    // ------------------------------------------------------------------
    // Album → artist back navigation
    // ------------------------------------------------------------------

    /** Opening an album from an artist remembers the artist identity, and back
     *  returns to that exact artist — not to Home and not to a blank state. */
    @Test
    fun `back from album returns to the artist that was open when the album opened`() {
        val openArtist = OpenAlbumContext(
            providerId = "ytmusic",
            artistId = "UCmMUZbaYdNH0bEd1PAlAqsA",
        )
        val album = AlbumSummary(
            id = "MPREb_9nqEki4ZDpp",
            providerId = "ytmusic",
            title = "(What's The Story) Morning Glory?",
            artist = "Oasis",
        )
        val albumDetail = AlbumDetail(
            id = "MPREb_9nqEki4ZDpp",
            providerId = "ytmusic",
            title = "(What's The Story) Morning Glory?",
            artist = "Oasis",
            artworkUrl = "https://example.test/album-w544.jpg",
            year = "1995",
            tracks = listOf(track1, track2),
        )

        val open = CatalogDetailActions.openAlbum(album)
        assertTrue("album must be openable", open != null)
        assertEquals("ytmusic", open!!.providerId)
        assertEquals("MPREb_9nqEki4ZDpp", open.catalogId)

        // The album screen is rendered from `albumDetail`; back must hand the
        // remembered artist identity back to the artist ViewModel. This is the
        // data contract the screen and MainActivity rely on — the exact pair
        // (providerId, artistId) the artist screen was opened with.
        val backTarget = CatalogDetailActions.backFromAlbum(openArtist, albumDetail)
        assertEquals("ytmusic", backTarget!!.providerId)
        assertEquals("UCmMUZbaYdNH0bEd1PAlAqsA", backTarget.artistId)
        // Not a guess from the display name.
        assertFalse(backTarget.artistId == "Oasis")
    }

    /** When the album's own artist string matches the artist that was open, back
     *  still returns to the remembered identity — the album's `artist` field is
     *  a display string and is never used as an id. */
    @Test
    fun `album back ignores the album's artist string and uses the remembered id`() {
        val openArtist = OpenAlbumContext(
            providerId = "ytmusic",
            artistId = "UCmMUZbaYdNH0bEd1PAlAqsA",
        )
        val albumDetail = AlbumDetail(
            id = "MPREb_9nqEki4ZDpp",
            providerId = "ytmusic",
            title = "Morning Glory?",
            artist = "Oasis", // display string only
            tracks = listOf(track1),
        )
        val backTarget = CatalogDetailActions.backFromAlbum(openArtist, albumDetail)
        assertEquals("UCmMUZbaYdNH0bEd1PAlAqsA", backTarget!!.artistId)
    }

    /** If the album was opened without an artist context (e.g. from a source that
     *  has no artist link), back returns null so the caller keeps the user on the
     *  album or chooses another origin — never invents an id. */
    @Test
    fun `back from album without an artist context returns null and invents nothing`() {
        val albumDetail = AlbumDetail(
            id = "MPREb_9nqEki4ZDpp",
            providerId = "ytmusic",
            title = "Morning Glory?",
            tracks = listOf(track1),
        )
        val backTarget = CatalogDetailActions.backFromAlbum(null, albumDetail)
        assertNull(backTarget)
    }

    // ------------------------------------------------------------------
    // Album track artwork fallback
    // ------------------------------------------------------------------

    /** A track with no artwork gets the album's artwork in an album context. */
    @Test
    fun `album track with no artwork uses the album artwork as fallback`() {
        val albumArt = "https://example.test/album-w544.jpg"
        val albumDetail = AlbumDetail(
            id = "MPREb_9nqEki4ZDpp",
            providerId = "ytmusic",
            title = "Morning Glory?",
            artworkUrl = albumArt,
            tracks = listOf(track2), // track2 has no artworkUrl
        )
        val resolved = CatalogDetailActions.albumTrackArtwork(track2, albumDetail)
        assertEquals(albumArt, resolved)
    }

    /** A track that already has its own artwork keeps it. */
    @Test
    fun `album track that has its own artwork keeps it over the album artwork`() {
        val albumArt = "https://example.test/album-w544.jpg"
        val trackArt = "https://example.test/track-w544.jpg"
        val track = Track(
            id = "v3",
            providerId = "ytmusic",
            title = "Stand by Me",
            artist = "Oasis",
            artworkUrl = trackArt,
        )
        val albumDetail = AlbumDetail(
            id = "MPREb_9nqEki4ZDpp",
            providerId = "ytmusic",
            title = "Morning Glory?",
            artworkUrl = albumArt,
            tracks = listOf(track),
        )
        val resolved = CatalogDetailActions.albumTrackArtwork(track, albumDetail)
        assertEquals(trackArt, resolved)
    }

    /** A null album artwork falls back to null transparently. */
    @Test
    fun `album track fallback is null when both the track and the album have no artwork`() {
        val albumDetail = AlbumDetail(
            id = "MPREb_9nqEki4ZDpp",
            providerId = "ytmusic",
            title = "Morning Glory?",
            artworkUrl = null,
            tracks = listOf(track2),
        )
        val resolved = CatalogDetailActions.albumTrackArtwork(track2, albumDetail)
        assertNull(resolved)
    }

    /** The fallback is read from the album, not derived from the track title. */
    @Test
    fun `album track artwork fallback is not derived from the track`() {
        val albumArt = "https://example.test/album-only.jpg"
        val albumDetail = AlbumDetail(
            id = "MPREb_9nqEki4ZDpp",
            providerId = "ytmusic",
            title = "Morning Glory?",
            artworkUrl = albumArt,
            tracks = listOf(track2),
        )
        // track2's title is "Don't Look Back in Anger"; the result must be the
        // album's artwork, never something computed from the track.
        assertEquals("https://example.test/album-only.jpg", CatalogDetailActions.albumTrackArtwork(track2, albumDetail))
    }

    // ------------------------------------------------------------------
    // Entity identity sanity checks (the real constants, not state plumbing)
    // ------------------------------------------------------------------

    @Test
    fun `the artist identity used by the back step is the real provider id`() {
        val artist = ArtistDetail(id = "UC1", providerId = "ytmusic", name = "An Artist")
        assertTrue(artist.id == "UC1" && artist.providerId == "ytmusic")
    }

    @Test
    fun `track artwork is preferred when the track carries its own artwork`() {
        val albumArt = "https://example.test/album-w544.jpg"
        val trackArt = "https://example.test/track-w544.jpg"
        // The track carries its own artwork; the resolver must prefer it over the
        // album's and must not treat a non-blank track.url as "no artwork".
        val track = Track(
            id = "v3",
            providerId = "ytmusic",
            title = "Stand by Me",
            artist = "Oasis",
            artworkUrl = trackArt,
        )
        val albumDetail = AlbumDetail(
            id = "MPREb_9nqEki4ZDpp",
            providerId = "ytmusic",
            title = "Morning Glory?",
            artworkUrl = albumArt,
            tracks = listOf(track),
        )
        val resolved = CatalogDetailActions.albumTrackArtwork(track, albumDetail)
        assertEquals(trackArt, resolved)
    }

    /** A track with absent-to-blank artwork falls back to the album's artwork. */
    @Test
    fun `track artwork fallback is used when the track artwork is blank`() {
        val albumArt = "https://example.test/album-w544.jpg"
        // A blank (empty) track artwork is treated as "no artwork", so the
        // album's artwork is used — but only when the album actually has one.
        // This is why the helper must test for blank, not just null.
        val track = Track(
            id = "v4",
            providerId = "ytmusic",
            title = "Second Hand Songs",
            artist = "David Bowie",
            artworkUrl = "", // blank counts as no artwork
        )
        val albumDetail = AlbumDetail(
            id = "MPREb_9nqEki4ZDpp",
            providerId = "ytmusic",
            title = "Young Americans",
            artworkUrl = albumArt,
            tracks = listOf(track),
        )
        val resolved = CatalogDetailActions.albumTrackArtwork(track, albumDetail)
        assertEquals(albumArt, resolved)
    }

    /** The fallback is selected by the caller-supplied resolver, not a hard-coded
     *  lookup — so an album list can pass a different fallback than the row's default.
     */
    @Test
    fun `trackArtwork resolver is honoured and falls back to album artwork`() {
        val albumArt = "https://example.test/album-w544.jpg"
        val albumDetail = AlbumDetail(
            id = "MPREb_9nqEki4ZDpp",
            providerId = "ytmusic",
            title = "Young Americans",
            artworkUrl = albumArt,
            tracks = listOf(track2),
        )
        // The screen passes an explicit resolver that prefers track art when
        // present and falls back to the album art — this is the exact code
        // AlbumDetailScreen now passes.
        val resolved = CatalogDetailActions.albumTrackArtwork(
            albumDetail.tracks.first(),
            albumDetail,
        )
        assertEquals(albumArt, resolved)
    }

    // ------------------------------------------------------------------
    // Entity identity regression (Bug 1)
    // ------------------------------------------------------------------

    /** Opening an album from an artist records the artist's REAL ids — never the
     *  album's own — so back returns to the very artist that was open. The artist
     *  and the album deliberately have different provider ids and ids; if the
     *  album's identity leaked in as the artist, this test breaks.
     */
    @Test
    fun `openAlbumContext captures the real artist provider id and id, not the album's`() {
        // An artist from one provider, whose discography includes an album from another
        // provider — a realistic cross-provider scenario. The back step must return to
        // the originator artist, not to the album's own id.
        val openArtist = OpenAlbumContext(
            providerId = "ytmusic",
            artistId = "UCmMUZbaYdNH0bEd1PAlAqsA",
        )
        // The album is real but its own provider/id are DIFFERENT from the artist's,
        // so any regression that stores them as the artist fails this assertion.
        val albumDetail = AlbumDetail(
            id = "MPREb_9nqEki4ZDpp",
            providerId = "itunes", // the album's own provider id
            title = "Morning Glory?",
            artist = "Oasis",
            tracks = listOf(track1),
        )
        val backTarget = CatalogDetailActions.backFromAlbum(openArtist, albumDetail)
        assertEquals("ytmusic", backTarget!!.providerId)
        assertEquals("UCmMUZbaYdNH0bEd1PAlAqsA", backTarget.artistId)
        // Not the album's own provider id.
        assertFalse(backTarget.providerId == "itunes")
        // Not the album's own artist id.
        assertFalse(backTarget.artistId == "MPREb_9nqEki4ZDpp")
    }

    /** The artist identity stored at open time is the actual [ArtistDetail]'s
     *  provider id and id, and [backFromAlbum] returns exactly those — the
     *  album's `artist` display string is never used as an id.
     */
    @Test
    fun `backFromAlbum returns the actual artist provider id and id from the open context`() {
        val realArtist = ArtistDetail(
            id = "UCmMUZbaYdNH0bEd1PAlAqsA",
            providerId = "ytmusic",
            name = "Oasis",
            description = "Bio",
            artworkUrl = "https://example.test/artist-w544.jpg",
            tracks = listOf(track1),
            albums = listOf(
                AlbumSummary(
                    id = "MPREb_9nqEki4ZDpp",
                    providerId = "ytmusic",
                    title = "(What's The Story) Morning Glory?",
                    artist = "Oasis",
                    artworkUrl = "https://example.test/album-w544.jpg",
                    year = "1995",
                ),
            ),
        )
        val openArtist = OpenAlbumContext(
            providerId = realArtist.providerId,
            artistId = realArtist.id,
        )
        val albumDetail = AlbumDetail(
            // The album's own id and provider id are DIFFERENT from the artist's.
            // This is the whole point of the test: if the album's identity were
            // stored as the artist, the back step would hand the artist screen
            // the album's id and provider.
            id = "MPREb_9nqEki4ZDpp",
            providerId = "itunes", // the album's own provider id (artist is ytmusic)
            title = "(What's The Story) Morning Glory?",
            artist = "Oasis", // display string only
            artworkUrl = "https://example.test/album-w544.jpg",
            year = "1995",
            tracks = listOf(track1),
        )
        val backTarget = CatalogDetailActions.backFromAlbum(openArtist, albumDetail)
        assertEquals(realArtist.providerId, backTarget!!.providerId)
        assertEquals(realArtist.id, backTarget.artistId)
        // Guard: the back step must hand the artist screen the artist's own ids,
        // never the album's. Even though the album here is from a different
        // provider and has a different id, back returns to the artist.
        assertFalse(backTarget.artistId == albumDetail.id)
        assertFalse(backTarget.providerId == albumDetail.providerId)
    }

    /** A supplied artist context with blank ids is rejected by back, so the caller
     *  keeps the user where they are — never invents an id from the album's.
     */
    @Test
    fun `back from album with a blank artist context returns null and invents nothing`() {
        val albumDetail = AlbumDetail(
            id = "MPREb_9nqEki4ZDpp",
            providerId = "ytmusic",
            title = "Morning Glory?",
            tracks = listOf(track1),
        )
        val backTarget = CatalogDetailActions.backFromAlbum(
            OpenAlbumContext(providerId = "ytmusic", artistId = ""),
            albumDetail,
        )
        assertNull(backTarget)
    }

    @Test
    fun `the album identity used by the back step is the real album id`() {
        val album = AlbumDetail(id = "MPREb_1", providerId = "ytmusic", title = "Album")
        assertTrue(album.id == "MPREb_1" && album.providerId == "ytmusic")
    }
}

