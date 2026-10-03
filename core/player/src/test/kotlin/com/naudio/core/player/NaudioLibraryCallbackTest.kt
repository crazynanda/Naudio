package com.naudio.core.player

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import com.naudio.core.model.Track
import org.robolectric.Robolectric
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M18 regression tests for the session callback's controller split.
 *
 * The regression: [NaudioLibraryCallback] intercepted `onSetMediaItems` /
 * `onAddMediaItems` for **every** controller and pushed them through the
 * Android Auto URI-less resolver, so the app's own
 * [MediaControllerPlaybackController] — which writes fully resolved, URI-bearing
 * items — never got its media to ExoPlayer.
 *
 * The fix branches on Media3's own controller identity
 * ([MediaSession.ControllerInfo.getPackageName]):
 *
 *  - this app's package -> the items are returned untouched;
 *  - anything else (Android Auto, Assistant, Bluetooth, or the unverified
 *    `android.media.session` placeholder) -> the existing Auto resolver path.
 *
 * The last group of tests wires the callback's answer through the real
 * [UriLessWriteFilter] to prove the safety invariant end to end: an unplayable
 * item cannot reach ExoPlayer.
 */
@RunWith(RobolectricTestRunner::class)
class NaudioLibraryCallbackTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val libraryDispatcher: CoroutineDispatcher = Dispatchers.Unconfined

    private lateinit var browseTree: LibraryBrowseTree
    private lateinit var bridge: RecordingPlaybackBridge
    private lateinit var session: MediaLibraryService.MediaLibrarySession
    private lateinit var callback: NaudioLibraryCallback

    private val appController = controllerInfo(APP_PACKAGE)
    private val autoController = controllerInfo(AUTO_PACKAGE)

    @Before
    fun setUp() {
        browseTree = LibraryBrowseTree()
        bridge = RecordingPlaybackBridge()
        callback = NaudioLibraryCallback(
            appPackageName = APP_PACKAGE,
            browseTree = browseTree,
            bridge = bridge,
            requestResolver = AutoPlaybackRequestResolver(browseTree, bridge),
            scope = scope,
            libraryDispatcher = libraryDispatcher,
        )
        // A real session object: Media3's callback signatures take it, and the
        // tests below hand it straight back, exactly as the session stub does.
        // MediaLibrarySession.Builder requires a MediaLibraryService context.
        val service = Robolectric.buildService(TestMediaLibraryService::class.java).create().get()
        session = MediaLibraryService.MediaLibrarySession.Builder(
            service,
            RecordingPlayer(),
            callback,
        ).build()
    }

    @After
    fun tearDown() {
        session.release()
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // FIX 2: the app's own controller keeps its normal, URI-bearing path
    // ------------------------------------------------------------------

    @Test
    fun `app uri bearing set media items pass through unchanged`() {
        val items = listOf(
            playableItem("file:///sdcard/Music/NaudioLongTone.wav", "t1"),
            playableItem("file:///sdcard/Music/NaudioShortLoop.wav", "t2"),
        )

        val result = get(
            callback.onSetMediaItems(session, appController, items, /* startIndex = */ 1, 12_000L),
        )

        assertEquals(items, result.mediaItems)
        assertEquals(1, result.startIndex)
        assertEquals(12_000L, result.startPositionMs)
        // The Auto resolver must not have been touched at all.
        assertTrue(bridge.played.isEmpty())
    }

    @Test
    fun `app uri bearing add media items pass through unchanged`() {
        val items = listOf(playableItem("content://media/external/audio/media/38", "t1"))

        val result = get(callback.onAddMediaItems(session, appController, items))

        assertEquals(items, result)
        assertTrue(bridge.played.isEmpty())
    }

    @Test
    fun `app unset start index is preserved as the Media3 sentinel`() {
        val items = listOf(playableItem("file:///sdcard/Music/NaudioShortLoop.wav", "t1"))

        val result = get(
            callback.onSetMediaItems(session, appController, items, C.INDEX_UNSET, C.TIME_UNSET),
        )

        assertEquals(C.INDEX_UNSET, result.startIndex)
        assertEquals(C.TIME_UNSET, result.startPositionMs)
    }

    @Test
    fun `app start index past the end is clamped instead of crashing`() {
        // ExoPlayer throws IllegalSeekPositionException when startWindowIndex >=
        // windowCount on a non-empty timeline; the callback must not pass that on.
        val items = listOf(playableItem("file:///sdcard/Music/NaudioShortLoop.wav", "t1"))

        val result = get(callback.onSetMediaItems(session, appController, items, /* startIndex = */ 9, 0L))

        assertEquals(0, result.startIndex)
    }

    // ------------------------------------------------------------------
    // The Auto path is untouched
    // ------------------------------------------------------------------

    @Test
    fun `external uri less set media items still enter auto resolution`() {
        val mediaId = playlistMediaId("b")

        val result = get(
            callback.onSetMediaItems(session, autoController, listOf(uriLessItem(mediaId)), 0, 0L),
        )

        // Delegated through the bridge exactly as before M18: the whole playlist
        // at the tapped index.
        val (tracks, index) = bridge.played.single()
        assertEquals(listOf("a", "b", "c"), tracks.map { it.id })
        assertEquals(1, index)
        // The session acknowledges with the requested (URI-less) item.
        assertEquals(listOf(mediaId), result.mediaItems.map { it.mediaId })
        assertEquals(0, result.startIndex)
    }

    @Test
    fun `external uri less add media items still enter auto resolution`() {
        val mediaId = MediaItemMapper.trackIdOf("itunes", "f2", MediaItemMapper.FAVORITES_CONTEXT)!!

        val result = get(callback.onAddMediaItems(session, autoController, listOf(uriLessItem(mediaId))))

        val (tracks, index) = bridge.played.single()
        assertEquals(listOf("f2", "f1"), tracks.map { it.id })
        assertEquals(0, index)
        assertEquals(listOf(mediaId), result.map { it.mediaId })
    }

    @Test
    fun `an external id that is not in the library resolves to nothing`() {
        val mediaId = MediaItemMapper.trackIdOf("local", "missing", playlistId = PLAYLIST_ID)!!

        val result = get(callback.onSetMediaItems(session, autoController, listOf(uriLessItem(mediaId)), 0, 0L))

        assertTrue(result.mediaItems.isEmpty())
        assertTrue(bridge.played.isEmpty())
    }

    @Test
    fun `a history entry keeps resolving through the recent history list`() {
        val mediaId = MediaItemMapper.trackIdOf("local", "h2", MediaItemMapper.HISTORY_CONTEXT)!!

        get(callback.onSetMediaItems(session, autoController, listOf(uriLessItem(mediaId)), 0, 0L))

        val (tracks, index) = bridge.played.single()
        assertEquals(listOf("h3", "h2", "h1"), tracks.map { it.id })
        assertEquals(1, index)
    }

    // ------------------------------------------------------------------
    // The safety invariant, composed: callback answer -> filter -> player
    // ------------------------------------------------------------------

    @Test
    fun `app set media items reach the player through the filter`() {
        val player = RecordingPlayer()
        val filter = UriLessWriteFilter(player)
        val items = listOf(
            playableItem("file:///sdcard/Music/NaudioLongTone.wav", "t1"),
            playableItem("file:///sdcard/Music/NaudioShortLoop.wav", "t2"),
        )

        val result = get(callback.onSetMediaItems(session, appController, items, 1, 0L))
        filter.setMediaItems(result.mediaItems.toMutableList(), result.startIndex, result.startPositionMs)

        // This is the mobile playback path end to end: the app's own resolved
        // items are what ExoPlayer loads.
        assertEquals(items, player.setCalls.single().items)
        assertEquals(1, player.setCalls.single().startIndex)
    }

    @Test
    fun `app add media items reach the player through the filter`() {
        val player = RecordingPlayer()
        val filter = UriLessWriteFilter(player)
        val items = listOf(playableItem("content://media/external/audio/media/39", "t1"))

        get(callback.onAddMediaItems(session, appController, items)).forEach { filter.addMediaItem(it) }

        assertEquals(items, player.addCalls.single().items)
    }

    @Test
    fun `an invalid uri less external item never reaches the player`() {
        val player = RecordingPlayer()
        val filter = UriLessWriteFilter(player)

        val result = get(
            callback.onSetMediaItems(session, autoController, listOf(uriLessItem(playlistMediaId("b"))), 0, 0L),
        )
        filter.setMediaItems(result.mediaItems.toMutableList(), result.startIndex, result.startPositionMs)

        // Delegated to the coordinator (that is the real playback), but the
        // URI-less acknowledgement itself is stopped before ExoPlayer.
        assertEquals(1, bridge.played.size)
        assertTrue(player.setCalls.isEmpty())
    }

    @Test
    fun `an external id that resolves to nothing never reaches the player`() {
        val player = RecordingPlayer()
        val filter = UriLessWriteFilter(player)

        val result = get(
            callback.onSetMediaItems(
                session,
                autoController,
                listOf(uriLessItem(MediaItemMapper.trackIdOf("local", "gone", playlistId = PLAYLIST_ID)!!)),
                0,
                0L,
            ),
        )
        filter.setMediaItems(result.mediaItems.toMutableList(), result.startIndex, result.startPositionMs)

        assertTrue(player.setCalls.isEmpty())
    }

    @Test
    fun `an app controller cannot smuggle a uri less item to the player`() {
        // The app branch returns items verbatim by design; the filter is what
        // guarantees a URI-less item still cannot be loaded.
        val player = RecordingPlayer()
        val filter = UriLessWriteFilter(player)

        val result = get(
            callback.onSetMediaItems(session, appController, listOf(uriLessItem("track:whatever")), 0, 0L),
        )
        filter.setMediaItems(result.mediaItems.toMutableList(), result.startIndex, result.startPositionMs)

        assertTrue(player.setCalls.isEmpty())
    }

    // ------------------------------------------------------------------
    // M14 / M17 browse behaviour must be untouched
    // ------------------------------------------------------------------

    @Test
    fun `library root and root children are intact`() {
        val root = get(callback.onGetLibraryRoot(session, autoController, null)).value!!
        assertEquals(MediaItemMapper.ROOT_MEDIA_ID, root.mediaId)

        val children = get(
            callback.onGetChildren(session, autoController, root.mediaId, 0, 50, null),
        ).value!!
        assertEquals(
            listOf(
                MediaItemMapper.FAVORITES_MEDIA_ID,
                MediaItemMapper.PLAYLISTS_MEDIA_ID,
                MediaItemMapper.HISTORY_MEDIA_ID,
            ),
            children.map { it.mediaId },
        )
    }

    @Test
    fun `favorites children keep the favorites context`() {
        val children = get(
            callback.onGetChildren(session, autoController, MediaItemMapper.FAVORITES_MEDIA_ID, 0, 50, null),
        ).value!!

        assertEquals(listOf("f2", "f1"), children.map { it.asTrack().trackId })
        children.forEach {
            assertEquals(MediaItemMapper.TrackContext.FAVORITES, it.asTrack().context)
        }
    }

    @Test
    fun `playlist children keep the playlist context`() {
        val playlists = get(
            callback.onGetChildren(session, autoController, MediaItemMapper.PLAYLISTS_MEDIA_ID, 0, 50, null),
        ).value!!
        assertEquals(listOf("playlist:$PLAYLIST_ID"), playlists.map { it.mediaId })

        val tracks = get(
            callback.onGetChildren(session, autoController, "playlist:$PLAYLIST_ID", 0, 50, null),
        ).value!!
        assertEquals(listOf("a", "b", "c"), tracks.map { it.asTrack().trackId })
        tracks.forEach {
            assertEquals(MediaItemMapper.TrackContext.PLAYLIST, it.asTrack().context)
        }
    }

    @Test
    fun `recently played children keep the history context`() {
        val children = get(
            callback.onGetChildren(session, autoController, MediaItemMapper.HISTORY_MEDIA_ID, 0, 50, null),
        ).value!!

        assertEquals(listOf("h3", "h2", "h1"), children.map { it.asTrack().trackId })
        children.forEach {
            assertEquals(MediaItemMapper.TrackContext.HISTORY, it.asTrack().context)
        }
    }

    @Test
    fun `item lookup still resolves track metadata through the bridge`() {
        val item = get(callback.onGetItem(session, autoController, playlistMediaId("a"))).value!!

        assertEquals("A", item.mediaMetadata.title.toString())
        assertTrue(bridge.lookups.contains("local" to "a"))
    }

    // ------------------------------------------------------------------
    // M16 command delegation must be untouched
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    @Test
    fun `next and previous are still delegated to the shared coordinator`() {
        val next = callback.onPlayerCommandRequest(
            session,
            autoController,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        )
        val previous = callback.onPlayerCommandRequest(
            session,
            autoController,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        assertEquals(SessionResult.RESULT_INFO_SKIPPED, next)
        assertEquals(SessionResult.RESULT_INFO_SKIPPED, previous)
        assertEquals(1, bridge.nextCalls)
        assertEquals(1, bridge.previousCalls)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `other player commands fall through to Media3`() {
        assertEquals(
            SessionResult.RESULT_SUCCESS,
            callback.onPlayerCommandRequest(session, autoController, Player.COMMAND_PLAY_PAUSE),
        )
        assertEquals(0, bridge.nextCalls)
        assertEquals(0, bridge.previousCalls)
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** The callback's helpers are synchronous on the test dispatcher. */
    private fun <T> get(future: com.google.common.util.concurrent.ListenableFuture<T>): T =
        runBlocking { future.get() }

    private fun controllerInfo(packageName: String): MediaSession.ControllerInfo =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            packageName,
            /* pid = */ 1234,
            /* uid = */ 10_000,
            /* libraryVersion = */ 100,
            /* interfaceVersion = */ 1,
            /* trusted = */ true,
            /* connectionHints = */ Bundle(),
            /* isPackageNameVerified = */ true,
        )

    private fun MediaItem.asTrack(): MediaItemMapper.MediaId.Track =
        MediaItemMapper.decode(mediaId) as MediaItemMapper.MediaId.Track

    private fun playlistMediaId(trackId: String): String =
        MediaItemMapper.trackIdOf("local", trackId, playlistId = PLAYLIST_ID)!!

    private fun playableItem(uri: String, id: String): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(id).build())
        .build()

    private fun uriLessItem(mediaId: String): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Browsed").build())
        .build()

    private companion object {
        const val APP_PACKAGE = "com.naudio.app"

        /** Android Auto's projection host package, i.e. a genuinely external controller. */
        const val AUTO_PACKAGE = "com.google.android.projection.gearhead"

        const val PLAYLIST_ID = 7L
    }
}

/**
 * [MediaLibraryService.MediaLibrarySession.Builder] casts its context to a
 * [MediaLibraryService], so the tests need a real one. It owns no logic — the
 * callback under test is handed in explicitly.
 */
class TestMediaLibraryService : MediaLibraryService() {
    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaLibraryService.MediaLibrarySession? = null
}

/** Deterministic library for the browse / resolution assertions. */
private class LibraryBrowseTree : AutoBrowseTreeProvider {
    val favorites = MutableStateFlow(
        listOf(
            Track("f2", "itunes", "Newest", "Artist"),
            Track("f1", "local", "Older", "Artist"),
        ),
    )
    val playlists = MutableStateFlow(listOf(AutoBrowseTreeProvider.PlaylistNode(7L, "Mix", 3)))
    val playlistTracks = MutableStateFlow(
        listOf(
            Track("a", "local", "A", "Artist"),
            Track("b", "local", "B", "Artist"),
            Track("c", "local", "C", "Artist"),
        ),
    )
    val history = MutableStateFlow(
        listOf(
            Track("h3", "local", "Third", "Artist"),
            Track("h2", "local", "Second", "Artist"),
            Track("h1", "local", "First", "Artist"),
        ),
    )

    override fun observeFavorites(): Flow<List<Track>> = favorites

    override fun observePlaylists(): Flow<List<AutoBrowseTreeProvider.PlaylistNode>> = playlists

    override fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> = playlistTracks

    override fun observeRecentHistory(): Flow<List<Track>> = history
}

/** Records everything the callback delegates to the shared playback path. */
private class RecordingPlaybackBridge : AutoPlaybackBridge {
    val played = mutableListOf<Pair<List<Track>, Int>>()
    val lookups = mutableListOf<Pair<String, String>>()
    var nextCalls = 0
    var previousCalls = 0

    override fun playCollection(tracks: List<Track>, startIndex: Int) {
        played += tracks to startIndex
    }

    override suspend fun findTrack(providerId: String, trackId: String): Track? {
        lookups += providerId to trackId
        return Track(trackId, providerId, "A", "Artist")
    }

    override fun skipToNext() {
        nextCalls++
    }

    override fun skipToPrevious() {
        previousCalls++
    }
}