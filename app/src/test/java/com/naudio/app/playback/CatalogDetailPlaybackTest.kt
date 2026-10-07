package com.naudio.app.playback

import com.naudio.app.ui.CatalogDetailActions
import com.naudio.core.model.AlbumDetail
import com.naudio.core.model.ArtistDetail
import com.naudio.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * M21 — playback integration for the catalog detail screens.
 *
 * The screens do not own a player: they compute a request through
 * [CatalogDetailActions] and hand it to the SAME [PlaybackCoordinator] the rest
 * of the app uses. These tests drive that path end to end and prove two things:
 *
 *  1. A detail screen queues through the existing coordinator — the one queue,
 *     the one resolution path — with no catalog-specific behaviour.
 *  2. M20's unplayable-track semantics are untouched by M21: an album whose
 *     tracks are unavailable still produces the terminal QUEUE_UNPLAYABLE, and
 *     an album mixing available and unavailable tracks still plays the
 *     available ones.
 *
 * The second point is the regression that matters: adding new entry points into
 * the queue is exactly where the M20 rules could have been accidentally bypassed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogDetailPlaybackTest {

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private lateinit var controller: FakePlaybackController
    private lateinit var trackDao: InMemoryTrackDao
    private lateinit var coordinator: PlaybackCoordinator

    /** A local (playable) track and a ytmusic (unplayable) one. */
    private val localTrack = Track("l1", "local", "Local Song", "Local Artist")
    private val unavailableTrack = Track("y1", "ytmusic", "YT Song", "YT Artist")

    private val artist = ArtistDetail(
        id = "UC1",
        providerId = "ytmusic",
        name = "An Artist",
        tracks = listOf(unavailableTrack),
    )

    private val album = AlbumDetail(
        id = "MPREb_1",
        providerId = "ytmusic",
        title = "An Album",
        // The unavailable track comes FIRST so "Play Album" must skip it before
        // reaching the playable one. A playable-first album would start at index
        // 0 and never exercise the skip at all.
        tracks = listOf(unavailableTrack, localTrack),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        controller = FakePlaybackController()
        trackDao = InMemoryTrackDao()
        coordinator = PlaybackCoordinator(
            playbackController = controller,
            libraryRepository = testLibraryRepository(),
            queueRepository = testQueueRepository(trackDao, FakeQueueDao()),
            scope = testScope,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Applies a screen's request exactly as MainActivity does. */
    private fun play(request: com.naudio.app.ui.PlaybackRequest?) {
        if (request == null) return
        coordinator.setQueue(request.tracks, request.startIndex)
        testScope.advanceUntilIdle()
    }

    @Test
    fun `play top songs on an unplayable artist yields the M20 terminal error`() {
        play(CatalogDetailActions.playAll(artist.tracks))

        // Identical to tapping an unplayable track anywhere else in the app.
        assertEquals(PlaybackError.QUEUE_UNPLAYABLE, coordinator.state.value.error)
        assertEquals(0, controller.loadCount)
    }

    @Test
    fun `play album skips an unavailable track and plays the playable one`() {
        play(CatalogDetailActions.playAll(album.tracks))

        // The playable local track is found and loaded; the YTM one is skipped.
        assertEquals(localTrack, controller.loadedTrack)
        assertEquals(1, controller.loadCount)
        assertEquals(PlaybackError.UNAVAILABLE, coordinator.state.value.error)
        // The queue still points at the playable track that is now loaded.
        assertEquals(1, coordinator.state.value.currentIndex)
    }

    @Test
    fun `play album starting on a playable track plays it without a skip`() {
        val playableFirst = album.copy(tracks = listOf(localTrack, unavailableTrack))

        play(CatalogDetailActions.playAll(playableFirst.tracks))

        // Index 0 is playable, so playback begins there and nothing is skipped —
        // no skip note is raised, which is the "starts where the album starts" case.
        assertEquals(localTrack, controller.loadedTrack)
        assertEquals(0, coordinator.state.value.currentIndex)
        assertNull(coordinator.state.value.error)
    }

    @Test
    fun `tapping a track in an album starts the queue at that index`() {
        val tracks = listOf(localTrack, Track("l2", "local", "Second Local", "Local Artist"))

        play(CatalogDetailActions.selectTrack(tracks, index = 1))

        assertEquals(tracks[1], controller.loadedTrack)
        assertEquals(1, coordinator.state.value.currentIndex)
    }

    @Test
    fun `play album preserves release order into the queue`() {
        val ordered = listOf(
            Track("l2", "local", "Second", "A"),
            Track("l1", "local", "First", "A"),
            Track("l3", "local", "Third", "A"),
        )

        play(CatalogDetailActions.playAll(ordered))

        // The coordinator stores what it was given, in the order it was given it.
        assertEquals(listOf("l2", "l1", "l3"), coordinator.state.value.queue.map { it.id })
    }

    @Test
    fun `an empty play request never reaches the coordinator`() {
        // The screen's button is disabled here; the null return is the second
        // half of the same guard, so an empty queue cannot be pushed.
        play(CatalogDetailActions.playAll(emptyList()))

        assertEquals(0, controller.loadCount)
        assertNull(coordinator.state.value.error)
        assertEquals(emptyList<Track>(), coordinator.state.value.queue)
    }
}