package com.naudio.core.player

import androidx.test.core.app.ApplicationProvider
import com.naudio.core.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the M14 MediaItem id scheme (encode/decode round trips, special
 * characters, malformed input) and the play-request delegation. Robolectric
 * provides android.util.Base64.
 */
@RunWith(RobolectricTestRunner::class)
class MediaItemMapperTest {

    // ------------------------------------------------------------------
    // MediaItemMapper: encode/decode
    // ------------------------------------------------------------------

    @Test
    fun `root and folder ids decode`() {
        assertEquals(MediaItemMapper.MediaId.Root, MediaItemMapper.decode(MediaItemMapper.ROOT_MEDIA_ID))
        assertEquals(MediaItemMapper.MediaId.Favorites, MediaItemMapper.decode(MediaItemMapper.FAVORITES_MEDIA_ID))
        assertEquals(MediaItemMapper.MediaId.Playlists, MediaItemMapper.decode(MediaItemMapper.PLAYLISTS_MEDIA_ID))
    }

    @Test
    fun `playlist id round trips`() {
        val id = MediaItemMapper.playlistIdOf(42L)
        assertEquals(MediaItemMapper.MediaId.Playlist(42L), MediaItemMapper.decode(id))
    }

    @Test
    fun `track id round trips with composite identity`() {
        val id = MediaItemMapper.trackIdOf("itunes", "1440847780")
        assertEquals(
            MediaItemMapper.MediaId.Track("itunes", "1440847780", MediaItemMapper.TrackContext.NONE, null),
            MediaItemMapper.decode(id),
        )
        // The spec invariant: decode(encode(providerId, trackId)) keeps the pair.
        val decoded = MediaItemMapper.decode(id) as MediaItemMapper.MediaId.Track
        assertEquals("itunes", decoded.providerId)
        assertEquals("1440847780", decoded.trackId)
    }

    @Test
    fun `provider and track ids containing special characters round trip`() {
        val hostileProvider = "p|a:i/\\s 💥"
        val hostileTrack = "t|a:i/\\s \"quoted\" \n id"
        val id = MediaItemMapper.trackIdOf(hostileProvider, hostileTrack)
        val decoded = MediaItemMapper.decode(id) as MediaItemMapper.MediaId.Track
        assertEquals(hostileProvider, decoded.providerId)
        assertEquals(hostileTrack, decoded.trackId)
        // Encoded ids never contain the raw separators.
        assertFalse(id!!.contains('|'))
        assertFalse(id.startsWith("track:p"))
    }

    @Test
    fun `favorites and playlist context round trip`() {
        val favorites = MediaItemMapper.trackIdOf("local", "t1", MediaItemMapper.FAVORITES_CONTEXT)
        assertEquals(
            MediaItemMapper.MediaId.Track("local", "t1", MediaItemMapper.TrackContext.FAVORITES, MediaItemMapper.FAVORITES_CONTEXT),
            MediaItemMapper.decode(favorites),
        )
        val inPlaylist = MediaItemMapper.trackIdOf("local", "t1", playlistId = 7L)
        assertEquals(
            MediaItemMapper.MediaId.Track("local", "t1", MediaItemMapper.TrackContext.PLAYLIST, 7L),
            MediaItemMapper.decode(inPlaylist),
        )
    }

    @Test
    fun `malformed ids are rejected not thrown`() {
        assertNull(MediaItemMapper.decode(null))
        assertNull(MediaItemMapper.decode(""))
        assertNull(MediaItemMapper.decode("unknown"))
        assertNull(MediaItemMapper.decode("playlist:"))
        assertNull(MediaItemMapper.decode("playlist:notanumber"))
        assertNull(MediaItemMapper.decode("playlist:-1"))
        assertNull(MediaItemMapper.decode("track:!!!not base64!!!"))
        assertNull(MediaItemMapper.decode("track:"))
        // Wrong context field.
        assertNull(MediaItemMapper.decode("track:" + android.util.Base64.encodeToString("1:a|1:b|junk".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)))
        // Length-prefix mismatch.
        assertNull(MediaItemMapper.decode("track:" + android.util.Base64.encodeToString("9:a|1:b|none".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)))
        // Empty identity components.
        assertNull(MediaItemMapper.trackIdOf("", "t1"))
        assertNull(MediaItemMapper.trackIdOf("itunes", ""))
    }

    @Test
    fun `very long ids encode within the decode bound or are rejected`() {
        // Long but legal ids round-trip.
        val longId = "i".repeat(1_500)
        val longTrack = "t".repeat(1_500)
        val decoded = MediaItemMapper.decode(MediaItemMapper.trackIdOf(longId, longTrack))
            as MediaItemMapper.MediaId.Track
        assertEquals(longId, decoded.providerId)
        assertEquals(longTrack, decoded.trackId)
        // Absurdly long ids exceed the decode-side 4096-char bound: encode
        // returns null instead of producing an undecodable id.
        assertNull(MediaItemMapper.trackIdOf("p".repeat(5_000), "t".repeat(5_000)))
    }

    @Test
    fun `whitespace and unicode ids round trip`() {
        val provider = "pro vider\twith ws"
        val track = "  padded  "
        val decoded = MediaItemMapper.decode(MediaItemMapper.trackIdOf(provider, track))
            as MediaItemMapper.MediaId.Track
        assertEquals(provider, decoded.providerId)
        assertEquals(track, decoded.trackId)
    }

    // ------------------------------------------------------------------
    // MediaItemMapper: MediaItem/metadata building
    // ------------------------------------------------------------------

    @Test
    fun `folder items are browsable not playable`() {
        val item = MediaItemMapper.folderItem(MediaItemMapper.FAVORITES_MEDIA_ID, "Favorites")
        assertTrue(item.mediaMetadata.isBrowsable!!)
        assertFalse(item.mediaMetadata.isPlayable!!)
        assertEquals(MediaItemMapper.FAVORITES_MEDIA_ID, item.mediaId)
    }

    @Test
    fun `playlist items are browsable not playable`() {
        val item = MediaItemMapper.playlistItem(playlistId = 3L, name = "Road trip", trackCount = 2)
        assertTrue(item.mediaMetadata.isBrowsable!!)
        assertFalse(item.mediaMetadata.isPlayable!!)
        assertEquals("playlist:3", item.mediaId)
        assertEquals("Road trip", item.mediaMetadata.title.toString())
    }

    @Test
    fun `track items are playable not browsable with full metadata`() {
        val track = Track(
            id = "1440847780",
            providerId = "itunes",
            title = "One More Time",
            artist = "Daft Punk",
            album = "Discovery",
            artworkUrl = "https://example.com/art.jpg",
            durationMs = 320_000L,
        )
        val item = MediaItemMapper.trackMediaItem(track)
        assertEquals(false, item.mediaMetadata.isBrowsable)
        assertEquals(true, item.mediaMetadata.isPlayable)
        assertEquals("One More Time", item.mediaMetadata.title.toString())
        assertEquals("Daft Punk", item.mediaMetadata.artist.toString())
        assertEquals("Discovery", item.mediaMetadata.albumTitle.toString())
        assertEquals("https://example.com/art.jpg", item.mediaMetadata.artworkUri.toString())
        // No playback URL or credentials ever leak into the metadata.
        assertNull(item.localConfiguration)
    }

    // ------------------------------------------------------------------
    // AutoPlaybackRequestResolver: playback delegation
    // ------------------------------------------------------------------

    private lateinit var browseTree: FakeResolverBrowseTree
    private lateinit var bridge: RecordingBridge
    private lateinit var resolver: AutoPlaybackRequestResolver

    @Before
    fun setUp() {
        browseTree = FakeResolverBrowseTree()
        bridge = RecordingBridge()
        resolver = AutoPlaybackRequestResolver(browseTree, bridge)
    }

    @Test
    fun `playlist track request delegates the whole playlist at the tapped index`() = runTest {
        val id = MediaItemMapper.trackIdOf("local", "b", playlistId = 7L)
        val outcome = resolver.resolve(id)

        assertTrue(outcome is AutoPlaybackRequestResolver.Outcome.Delegated)
        val (queuedTracks, queuedIndex) = bridge.played.single()
        assertEquals(listOf("a", "b", "c"), queuedTracks.map { it.id })
        assertEquals(1, queuedIndex)
    }

    @Test
    fun `favorites request delegates the favorites list`() = runTest {
        val outcome = resolver.resolve(MediaItemMapper.trackIdOf("itunes", "f2", MediaItemMapper.FAVORITES_CONTEXT))

        assertTrue(outcome is AutoPlaybackRequestResolver.Outcome.Delegated)
        val (tracks, index) = bridge.played.single()
        assertEquals(listOf("f2", "f1"), tracks.map { it.id }) // repository order kept
        assertEquals(0, index)
    }

    @Test
    fun `unknown track or malformed id yields NotFound and never delegates`() = runTest {
        assertTrue(resolver.resolve(MediaItemMapper.trackIdOf("local", "missing", playlistId = 7L)) is AutoPlaybackRequestResolver.Outcome.NotFound)
        assertTrue(resolver.resolve("track:garbage") is AutoPlaybackRequestResolver.Outcome.NotFound)
        assertTrue(resolver.resolve("playlist:7") is AutoPlaybackRequestResolver.Outcome.NotFound)
        assertTrue(resolver.resolve(null) is AutoPlaybackRequestResolver.Outcome.NotFound)
        assertTrue(bridge.played.isEmpty())
    }

    @Test
    fun `no context id never delegates`() = runTest {
        assertTrue(resolver.resolve(MediaItemMapper.trackIdOf("local", "a")) is AutoPlaybackRequestResolver.Outcome.NotFound)
        assertTrue(bridge.played.isEmpty())
    }
}

/** Deterministic browse tree for resolver tests. */
private class FakeResolverBrowseTree : AutoBrowseTreeProvider {
    val playlists = MutableStateFlow(
        listOf(
            AutoBrowseTreeProvider.PlaylistNode(7L, "Mix", 3),
        ),
    )
    val playlistTracks = MutableStateFlow(
        listOf(
            Track("a", "local", "A", "Artist"),
            Track("b", "local", "B", "Artist"),
            Track("c", "local", "C", "Artist"),
        ),
    )
    val favorites = MutableStateFlow(
        listOf(
            Track("f2", "itunes", "Newest", "Artist"),
            Track("f1", "local", "Older", "Artist"),
        ),
    )

    override fun observeFavorites(): Flow<List<Track>> = favorites

    override fun observePlaylists(): Flow<List<AutoBrowseTreeProvider.PlaylistNode>> = playlists

    override fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> = playlistTracks
}

/** Records playCollection delegations. */
private class RecordingBridge : AutoPlaybackBridge {
    val played = mutableListOf<Pair<List<Track>, Int>>()

    override fun playCollection(tracks: List<Track>, startIndex: Int) {
        played += tracks to startIndex
    }

    override suspend fun findTrack(providerId: String, trackId: String): Track? = null

    override fun skipToNext() = Unit

    override fun skipToPrevious() = Unit
}
