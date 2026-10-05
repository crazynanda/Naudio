package com.naudio.app.auto

import com.naudio.app.playback.FakePlaybackController
import com.naudio.app.playback.FakeQueueDao
import com.naudio.app.playback.PlaybackCoordinator
import com.naudio.app.playback.PlaybackError
import com.naudio.app.playback.testLibraryRepository
import com.naudio.app.playback.testQueueRepository
import com.naudio.app.playback.testTracks
import com.naudio.app.playback.ytmTrack
import com.naudio.core.player.PlaybackStatus
import com.naudio.data.repository.FavoritesRepository
import com.naudio.data.repository.HistoryRepository
import com.naudio.data.repository.PlaylistRepository
import com.naudio.data.repository.TransactionRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * M20 / Android Auto: the Auto playback path stays provider-agnostic and must
 * not leave the player in an ambiguous state when a collection cannot play.
 *
 * These tests drive the REAL [AutoPlaybackGateway] over the REAL
 * [PlaybackCoordinator] — the same shared coordinator the mobile UI uses. That
 * is the whole point of the M14 design and it is what makes these assertions
 * meaningful: there is deliberately no Auto-specific playback path to regress,
 * only the one coordinator behaviour.
 *
 * Nothing here is provider-specific. An "unavailable" track is simply one whose
 * provider resolves no source through the shared registry, which is what YouTube
 * Music looks like there today. No test contacts YouTube.
 *
 * The DAO doubles are the existing [AutoTreeFakeTrackDao] /
 * [AutoTreeFakePlaylistDao] / [AutoTreeFakeHistoryDao] shared with
 * [AutoBrowseTreeTest], so both Auto tests build the real repositories.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoPlaybackGatewayTest {

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private lateinit var controller: FakePlaybackController
    private lateinit var trackDao: AutoTreeFakeTrackDao
    private lateinit var coordinator: PlaybackCoordinator
    private lateinit var gateway: AutoPlaybackGateway

    private val localA = testTracks()[0]
    private val localB = testTracks()[1]
    private val ytmA = ytmTrack("y1")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        controller = FakePlaybackController()
        trackDao = AutoTreeFakeTrackDao()
        val playlistDao = AutoTreeFakePlaylistDao(trackDao)
        coordinator = PlaybackCoordinator(
            playbackController = controller,
            libraryRepository = testLibraryRepository(),
            queueRepository = testQueueRepository(trackDao, FakeQueueDao()),
            scope = testScope,
        )
        gateway = AutoPlaybackGateway(
            coordinator = coordinator,
            favoritesRepository = FavoritesRepository(trackDao),
            playlistRepository = PlaylistRepository(
                playlistDao = playlistDao,
                trackDao = trackDao,
                transactions = TransactionRunner { block -> block() },
            ),
            // The retention trim lives on the repository's scope; a TestScope
            // keeps it queued instead of racing these assertions.
            historyRepository = HistoryRepository(AutoTreeFakeHistoryDao(), testScope),
        )
    }

    private fun advanceUntilIdle() = testScope.advanceUntilIdle()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // The M14 shared-path guarantee must not regress
    // ------------------------------------------------------------------

    @Test
    fun `Auto play goes through the same coordinator as the mobile UI`() {
        gateway.playCollection(listOf(localA), startIndex = 0)
        advanceUntilIdle()

        assertEquals(1, controller.loadCount)
        assertEquals(localA, controller.loadedTrack)
        assertEquals(0, coordinator.state.value.currentIndex)
        assertNull(coordinator.state.value.error)
    }

    @Test
    fun `Auto play honours the requested start index`() {
        gateway.playCollection(listOf(localA, localB), startIndex = 1)
        advanceUntilIdle()

        assertEquals(localB, controller.loadedTrack)
        assertEquals(1, coordinator.state.value.currentIndex)
    }

    @Test
    fun `Auto skips an unavailable track and keeps playing the rest`() {
        gateway.playCollection(listOf(localA, ytmA, localB), startIndex = 0)
        advanceUntilIdle()

        assertEquals(localA, controller.loadedTrack)

        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(localB, controller.loadedTrack)
        assertEquals(PlaybackError.UNAVAILABLE, coordinator.state.value.error)
    }

    // ------------------------------------------------------------------
    // M20: an entirely unplayable Auto collection
    // ------------------------------------------------------------------

    @Test
    fun `an unplayable Auto collection reports a terminal error`() {
        gateway.playCollection(listOf(ytmA, ytmTrack("y2")), startIndex = 0)
        advanceUntilIdle()

        assertEquals(PlaybackError.QUEUE_UNPLAYABLE, coordinator.state.value.error)
        assertEquals(0, controller.loadCount)
    }

    @Test
    fun `an unplayable Auto collection stops the player and clears the loaded item`() {
        gateway.playCollection(listOf(localA), startIndex = 0)
        advanceUntilIdle()
        assertEquals(localA, controller.loadedTrack)
        val stopsBefore = controller.stopCalls

        gateway.playCollection(listOf(ytmA), startIndex = 0)
        advanceUntilIdle()

        // The stop is what keeps Media3 unambiguous: without it the previously
        // loaded item would keep playing while the visible queue pointed at an
        // unplayable track, and the MediaSession would advertise a stale entry.
        assertEquals(stopsBefore + 1, controller.stopCalls)
        assertNull(controller.loadedTrack)
        assertNull(controller.state.value.track)
    }

    @Test
    fun `Auto skip next over an unavailable item still reaches the next playable one`() {
        gateway.playCollection(listOf(localA, ytmA, localB), startIndex = 0)
        advanceUntilIdle()

        gateway.skipToNext()
        advanceUntilIdle()

        assertEquals(localB, controller.loadedTrack)
    }

    @Test
    fun `Auto skip previous is a safe no-op at the queue start`() {
        gateway.playCollection(listOf(localA), startIndex = 0)
        advanceUntilIdle()
        val loads = controller.loadCount

        gateway.skipToPrevious()
        advanceUntilIdle()

        assertEquals(loads, controller.loadCount)
        assertEquals(localA, controller.loadedTrack)
    }

    // ------------------------------------------------------------------
    // findTrack (M14 browse -> playback handoff) stays provider-agnostic
    // ------------------------------------------------------------------

    @Test
    fun `findTrack resolves a favorited track and reports an unknown id as null`() = runTest(dispatcher) {
        FavoritesRepository(trackDao).toggleFavorite(localA, true)
        testScope.advanceUntilIdle()

        assertEquals(localA, gateway.findTrack(localA.providerId, localA.id))
        assertNull(gateway.findTrack("nobody", "missing"))
    }
}