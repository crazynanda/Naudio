package com.naudio.provider.ytmusic

import com.naudio.core.model.Track
import com.naudio.provider.api.Page
import com.naudio.provider.innertube.api.YtMusicAlbum
import com.naudio.provider.innertube.api.YtMusicArtist
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M21 — catalog detail mapping in [YtMusicMetadataProvider].
 *
 * The provider's job here is narrow and worth pinning down exactly: translate
 * the backend's browse-shaped catalog values onto the provider-neutral domain
 * models, and preserve provider identity on every entity it hands back. It must
 * not invent an id, reorder an album, or swallow a failure.
 *
 * Everything runs against [FakeYtMusicBackend] — no network, no live YouTube.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YtMusicCatalogProviderTest {

    private fun provider(backend: FakeYtMusicBackend) = YtMusicMetadataProvider(backend)

    private val backendArtist = YtMusicArtist(
        id = "UCabc",
        name = "Rick Astley",
        description = "4.4M subscribers",
        artworkUrl = "https://img.example/artist.jpg",
        tracks = listOf(
            FakeYtMusicBackend.track("v1"),
            FakeYtMusicBackend.track("v2"),
        ),
        albums = listOf(
            YtMusicAlbum(id = "MPREb_1", title = "Whenever", artist = "Rick Astley", year = "1989"),
            YtMusicAlbum(id = "MPREb_2", title = "Second"),
        ),
    )

    private val backendAlbum = YtMusicAlbum(
        id = "MPREb_1",
        title = "Whenever You Need Somebody",
        artist = "Rick Astley",
        artworkUrl = "https://img.example/album.jpg",
        year = "1989",
        // Deliberately NOT alphabetical: release order must survive the mapping.
        tracks = listOf(
            FakeYtMusicBackend.track("zzz"),
            FakeYtMusicBackend.track("aaa"),
            FakeYtMusicBackend.track("mmm"),
        ),
    )

    // ------------------------------------------------------------------
    // Artist mapping
    // ------------------------------------------------------------------

    @Test
    fun `search results reach the app with the catalog ids the backend reported`() = runTest {
        // The hop the artist entry point sits on: the backend parsed an artist id
        // and an album id off the response, and the provider layer must hand both
        // on verbatim — it is the one place between the response and the Home row
        // where a track could quietly lose them.
        val linked = Track(
            id = "ZrOKjDZOtkA",
            providerId = "ytmusic",
            title = "Wonderwall",
            artist = "Oasis",
            album = "Morning Glory",
            artistId = "UCmMUZbaYdNH0bEd1PAlAqsA",
            albumId = "MPREb_9nqEki4ZDpp",
        )
        val plain = FakeYtMusicBackend.track("v2")
        val provider = provider(FakeYtMusicBackend(page = Page(listOf(linked, plain), null)))

        val tracks = provider.searchTracks("wonderwall", token = null, limit = 20).items

        assertEquals("UCmMUZbaYdNH0bEd1PAlAqsA", tracks[0].artistId)
        assertEquals("MPREb_9nqEki4ZDpp", tracks[0].albumId)
        // A track the backend could not link still reports none, rather than
        // inheriting its neighbour's.
        assertNull(tracks[1].artistId)
        assertNull(tracks[1].albumId)
    }

    @Test
    fun `the artist identity, artwork and discography all survive onto ArtistDetail`() = runTest {
        // The manual-test failure this pins down: the artist page loaded its songs
        // but rendered "Unknown" with placeholder artwork and no discography.
        // Whatever the backend parsed has to arrive intact on the domain object the
        // screen renders, or the screen has nothing to show.
        val oasis = YtMusicArtist(
            id = "UCmMUZbaYdNH0bEd1PAlAqsA",
            name = "Oasis",
            description = "Oasis were an English rock band formed in Manchester in 1991.",
            artworkUrl = "https://yt3.googleusercontent.com/oasis-w544-h544",
            tracks = listOf(FakeYtMusicBackend.track("ZrOKjDZOtkA")),
            albums = listOf(
                YtMusicAlbum(
                    id = "MPREb_9nqEki4ZDpp",
                    title = "(What's The Story) Morning Glory? (Remastered)",
                    artist = "Oasis",
                    artworkUrl = "https://lh3.googleusercontent.com/morning-glory-w544-h544",
                    year = "1995",
                ),
            ),
        )

        val detail = provider(FakeYtMusicBackend(artist = oasis)).getArtist("UCmMUZbaYdNH0bEd1PAlAqsA")

        assertEquals("Oasis", detail?.name)
        assertEquals("ytmusic", detail?.providerId)
        assertEquals("https://yt3.googleusercontent.com/oasis-w544-h544", detail?.artworkUrl)
        assertEquals("Oasis were an English rock band formed in Manchester in 1991.", detail?.description)
        assertEquals(listOf("ZrOKjDZOtkA"), detail?.tracks?.map { it.id })
        assertEquals(listOf("MPREb_9nqEki4ZDpp"), detail?.albums?.map { it.id })
        assertEquals("Oasis", detail?.albums?.single()?.artist)
        assertEquals("1995", detail?.albums?.single()?.year)
        assertFalse(detail?.isEmpty ?: true)
    }

    @Test
    fun `getArtist asks the backend for exactly the id the caller resolved`() = runTest {
        // The id a search row carried is the id the artist page is opened with —
        // no translation, no normalisation, no substituting the display name.
        val fake = FakeYtMusicBackend(artist = backendArtist)
        val provider = provider(fake)

        val detail = provider.getArtist("UCmMUZbaYdNH0bEd1PAlAqsA")

        assertEquals("UCmMUZbaYdNH0bEd1PAlAqsA", fake.lastArtistBrowseId)
        assertEquals("Rick Astley", detail?.name)
    }

    @Test
    fun `an artist maps every field the backend reported`() = runTest {
        val artist = provider(FakeYtMusicBackend(artist = backendArtist)).getArtist("UCabc")

        assertEquals("UCabc", artist?.id)
        assertEquals("Rick Astley", artist?.name)
        assertEquals("4.4M subscribers", artist?.description)
        assertEquals("https://img.example/artist.jpg", artist?.artworkUrl)
        assertEquals(listOf("v1", "v2"), artist?.tracks?.map { it.id })
        assertEquals(listOf("MPREb_1", "MPREb_2"), artist?.albums?.map { it.id })
    }

    @Test
    fun `provider identity is stamped on the artist and every nested entity`() = runTest {
        val artist = provider(FakeYtMusicBackend(artist = backendArtist)).getArtist("UCabc")

        assertEquals(YtMusicProviderIds.YTMUSIC, artist?.providerId)
        assertEquals(
            YtMusicProviderIds.YTMUSIC,
            artist?.albums?.map { it.providerId }?.distinct()?.single(),
        )
    }

    @Test
    fun `an artist with no optional fields keeps them absent rather than placeholder`() = runTest {
        val bare = YtMusicArtist(id = "UCbare", name = "Bare")
        val artist = provider(FakeYtMusicBackend(artist = bare)).getArtist("UCbare")

        assertNull(artist?.description)
        assertNull(artist?.artworkUrl)
        assertTrue(artist?.tracks.isNullOrEmpty())
        assertTrue(artist?.albums.isNullOrEmpty())
        // "Absent" must stay distinguishable from "empty string".
        assertTrue(artist?.isEmpty == true)
    }

    @Test
    fun `a blank artist id issues no request at all`() = runTest {
        val backend = FakeYtMusicBackend(artist = backendArtist)

        assertNull(provider(backend).getArtist("   "))

        // A malformed id must not burn a request against the backend.
        assertTrue(backend.calls.none { it == "artist" })
    }

    // ------------------------------------------------------------------
    // Album mapping
    // ------------------------------------------------------------------

    @Test
    fun `an album maps every field and PRESERVES release order`() = runTest {
        val album = provider(FakeYtMusicBackend(album = backendAlbum)).getAlbum("MPREb_1")

        assertEquals("MPREb_1", album?.id)
        assertEquals("Whenever You Need Somebody", album?.title)
        assertEquals("Rick Astley", album?.artist)
        assertEquals("1989", album?.year)
        assertEquals("https://img.example/album.jpg", album?.artworkUrl)
        // The load-bearing assertion: album order is part of the contract, and a
        // mapper that sorted here would silently reorder every release.
        assertEquals(listOf("zzz", "aaa", "mmm"), album?.tracks?.map { it.id })
    }

    @Test
    fun `an album summary carries list-entry data only`() = runTest {
        // The backend's release card carries no tracks, so the summary must not
        // gain any: fetching the full album is the album screen's job, not the
        // discography row's.
        val withTracks = backendArtist.copy(
            albums = listOf(YtMusicAlbum(id = "MPREb_x", title = "X", tracks = listOf(FakeYtMusicBackend.track("v9")))),
        )
        val artist = provider(FakeYtMusicBackend(artist = withTracks)).getArtist("UCabc")
        val summary = artist?.albums?.first()

        assertEquals("MPREb_x", summary?.id)
        assertEquals("X", summary?.title)
        // A summary has no track list at all — it is not AlbumDetail.
        assertNull(summary?.year)
    }

    @Test
    fun `a blank album id issues no request at all`() = runTest {
        val backend = FakeYtMusicBackend(album = backendAlbum)

        assertNull(provider(backend).getAlbum(""))

        assertTrue(backend.calls.none { it == "album" })
    }

    // ------------------------------------------------------------------
    // Failure propagation — no swallowing, no fabricated values
    // ------------------------------------------------------------------

    @Test
    fun `a transport failure propagates instead of becoming a fabricated empty page`() = runTest {
        val backend = FakeYtMusicBackend(artist = backendArtist).apply {
            catalogError = IllegalStateException("network down")
        }

        val thrown = runCatching { provider(backend).getArtist("UCabc") }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `the provider still delegates search and lookup exactly as before`() = runTest {
        val backend = FakeYtMusicBackend()
        val provider = provider(backend)

        provider.searchTracks("query")
        provider.lookupTrack("v1")

        assertEquals(listOf("search", "song"), backend.calls)
    }

    @Test
    fun `each catalog call carries exactly the browse id the caller supplied`() = runTest {
        val backend = FakeYtMusicBackend(artist = backendArtist, album = backendAlbum)
        val provider = provider(backend)

        provider.getArtist("UCxyz")
        provider.getAlbum("MPREb_other")

        // Proves the id is threaded through rather than reconstructed, and that
        // neither call reuses the other's identity.
        assertEquals("UCxyz", backend.lastArtistBrowseId)
        assertEquals("MPREb_other", backend.lastAlbumBrowseId)
    }
}