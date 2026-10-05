package com.naudio.app.playback

import com.naudio.app.ui.message
import com.naudio.core.player.PlaybackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M20: playback failure handling for queues whose tracks have no playable
 * source.
 *
 * Everything here is deterministic and offline. The "unavailable" tracks are
 * `ytmusic` tracks resolved by the existing [CoordinatorTestFakes] registry,
 * which has no YouTube Music playback provider registered — the same real
 * outcome the InnerTube backend produces today when it will not hand back a
 * legitimately playable URL. No test here touches YouTube.
 *
 * The contract under test:
 *  - an unavailable track in the middle of a queue is SKIPPED and playback
 *    continues (Cases A and B);
 *  - a queue with nothing playable is a TERMINAL run: the error is explicit
 *    ([PlaybackError.QUEUE_UNPLAYABLE], never the per-item UNAVAILABLE) and the
 *    player is stopped so nothing stale stays loaded (Case C);
 *  - skipping never changes repeat/shuffle semantics (M16);
 *  - the terminal state is recoverable by pressing Play again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackUnavailableQueueTest {

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private lateinit var controller: FakePlaybackController
    private lateinit var queueDao: FakeQueueDao
    private lateinit var trackDao: InMemoryTrackDao
    private lateinit var coordinator: PlaybackCoordinator

    private val localA = testTracks()[0]
    private val localB = testTracks()[1]
    private val localC = testTracks()[2]
    private val ytmA = ytmTrack("y1")
    private val ytmB = ytmTrack("y2")
    private val ytmC = ytmTrack("y3")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        controller = FakePlaybackController()
        queueDao = FakeQueueDao()
        trackDao = InMemoryTrackDao()
        coordinator = newCoordinator()
    }

    private fun newCoordinator(): PlaybackCoordinator = PlaybackCoordinator(
        playbackController = controller,
        libraryRepository = testLibraryRepository(),
        queueRepository = testQueueRepository(trackDao, queueDao),
        scope = testScope,
    )

    private fun advanceUntilIdle() = testScope.advanceUntilIdle()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // Case A — unavailable first, playable second
    // ------------------------------------------------------------------

    @Test
    fun `case A unavailable YTM then playable local plays the local track`() {
        coordinator.setQueue(listOf(ytmA, localB), startIndex = 0)
        advanceUntilIdle()

        assertEquals(1, controller.loadCount)
        assertEquals(localB, controller.loadedTrack)
        assertEquals(1, coordinator.state.value.currentIndex)
        // A skip happened, so the note is reported once — but playback continues,
        // so this is NOT the terminal error.
        assertEquals(PlaybackError.UNAVAILABLE, coordinator.state.value.error)
        // Playback continuing means nothing was stopped.
        assertEquals(0, controller.stopCalls)
    }

    // ------------------------------------------------------------------
    // Case B — playable, unavailable, playable
    // ------------------------------------------------------------------

    @Test
    fun `case B skips the unavailable middle track and plays both local tracks`() {
        coordinator.setQueue(listOf(localA, ytmA, localB), startIndex = 0)
        advanceUntilIdle()

        assertEquals(localA, controller.loadedTrack)
        assertEquals(1, controller.loadCount)

        // A ends -> advance: the YTM item is skipped, B plays.
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(2, controller.loadCount)
        assertEquals(localB, controller.loadedTrack)
        assertEquals(2, coordinator.state.value.currentIndex)
        assertEquals(PlaybackError.UNAVAILABLE, coordinator.state.value.error)
        assertEquals(0, controller.stopCalls)
    }

    // ------------------------------------------------------------------
    // Case C — nothing in the queue is playable
    // ------------------------------------------------------------------

    @Test
    fun `case C an entirely unavailable queue reports a terminal error`() {
        coordinator.setQueue(listOf(ytmA, ytmB, ytmC), startIndex = 0)
        advanceUntilIdle()

        assertEquals(PlaybackError.QUEUE_UNPLAYABLE, coordinator.state.value.error)
        assertEquals(0, controller.loadCount)
        assertEquals(2, coordinator.state.value.currentIndex)
        assertEquals(ytmC, coordinator.state.value.currentTrack)
    }

    @Test
    fun `case C stops the player so no stale item stays loaded`() {
        // Start on something playable so there IS a stale item to catch.
        coordinator.setQueue(listOf(localA), startIndex = 0)
        advanceUntilIdle()
        assertEquals(localA, controller.loadedTrack)
        val stopsBefore = controller.stopCalls

        coordinator.setQueue(listOf(ytmA, ytmB), startIndex = 0)
        advanceUntilIdle()

        // Without the stop the previously loaded track would keep playing while
        // the queue pointed at an unplayable one — the ambiguity M20 removes.
        assertEquals(stopsBefore + 1, controller.stopCalls)
        assertNull(controller.loadedTrack)
        assertNull(controller.state.value.track)
        assertEquals(PlaybackError.QUEUE_UNPLAYABLE, coordinator.state.value.error)
    }

    @Test
    fun `case C persists the settled index so the unplayable item stays shown`() {
        coordinator.setQueue(listOf(ytmA, ytmB, ytmC), startIndex = 0)
        advanceUntilIdle()

        assertEquals(2, queueDao.state.value?.currentIndex)
    }

    @Test
    fun `case C surfaces the terminal error exactly once, never looping`() {
        coordinator.setQueue(listOf(ytmA, ytmB, ytmC), startIndex = 0)
        advanceUntilIdle()

        // A stray ENDED after the terminal stop must not restart anything: the
        // auto-advance gate is closed by the stop.
        val loadsAfterFirstRun = controller.loadCount
        val stopsAfterFirstRun = controller.stopCalls
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(loadsAfterFirstRun, controller.loadCount)
        assertEquals(stopsAfterFirstRun, controller.stopCalls)
        assertEquals(PlaybackError.QUEUE_UNPLAYABLE, coordinator.state.value.error)
    }

    @Test
    fun `terminal state is retryable by pressing play again`() {
        coordinator.setQueue(listOf(ytmA, ytmB), startIndex = 0)
        advanceUntilIdle()
        assertEquals(PlaybackError.QUEUE_UNPLAYABLE, coordinator.state.value.error)
        coordinator.onErrorShown()
        assertNull(coordinator.state.value.error)

        // Re-resolution runs from the current index; still nothing playable, so
        // it terminates again rather than crashing or looping.
        coordinator.onTogglePlayPause()
        advanceUntilIdle()

        assertEquals(PlaybackError.QUEUE_UNPLAYABLE, coordinator.state.value.error)
        assertEquals(0, controller.loadCount)
    }

    // ------------------------------------------------------------------
    // Mixed-provider continuation (M20 test item 9)
    // ------------------------------------------------------------------

    @Test
    fun `mixed provider queue keeps advancing across the unavailable item`() {
        coordinator.setQueue(listOf(localA, ytmA, localB, ytmB, localC), startIndex = 0)
        advanceUntilIdle()

        val expected = listOf(localA, localB, localC)
        var index = 0
        expected.forEach { track ->
            if (index > 0) {
                controller.emitStatus(PlaybackStatus.ENDED)
                advanceUntilIdle()
            }
            assertEquals(track, controller.loadedTrack)
            index++
        }

        assertEquals(3, controller.loadCount)
        assertEquals(4, coordinator.state.value.currentIndex)
        assertEquals(localC, controller.loadedTrack)
        assertEquals(0, controller.stopCalls)
    }

    @Test
    fun `every provider in the queue is resolved through the shared registry`() {
        // Routing is by Track.providerId. Starting ON the ytmusic item, and with
        // nothing playable behind it, proves the registry holds no YouTube Music
        // playback provider AND that the coordinator falls back to no other
        // provider — it must not try to resolve the track some other way.
        coordinator.setQueue(listOf(localA, ytmA), startIndex = 1)
        advanceUntilIdle()

        assertEquals(0, controller.loadCount)
        assertNull(controller.loadedTrack)
        assertEquals(PlaybackError.QUEUE_UNPLAYABLE, coordinator.state.value.error)
        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(ytmA, coordinator.state.value.currentTrack)
    }

    // ------------------------------------------------------------------
    // M16 repeat / shuffle not regressed by the new terminal stop
    // ------------------------------------------------------------------

    @Test
    fun `repeat all still wraps past an unavailable track`() {
        coordinator.setQueue(listOf(localA, ytmA, localB), startIndex = 0)
        advanceUntilIdle()
        controller.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ALL)
        advanceUntilIdle()

        assertEquals(localA, controller.loadedTrack)

        // Loop the first item -> repeat-all must advance to 1 (YTM), which is
        // then skipped so the queue lands on localB.
        controller.emitRepeatLoop(androidx.media3.common.Player.REPEAT_MODE_ALL)
        advanceUntilIdle()

        assertEquals(localB, controller.loadedTrack)
        assertEquals(2, controller.loadCount)
        assertEquals(PlaybackError.UNAVAILABLE, coordinator.state.value.error)
    }

    @Test
    fun `repeat all over an entirely unavailable queue terminates instead of spinning`() {
        coordinator.setQueue(listOf(ytmA, ytmB), startIndex = 0)
        advanceUntilIdle()
        controller.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ALL)
        advanceUntilIdle()

        val stopsBefore = controller.stopCalls
        controller.emitRepeatLoop(androidx.media3.common.Player.REPEAT_MODE_ALL)
        advanceUntilIdle()

        // The repeat transition must not resurrect a queue that cannot play.
        assertEquals(stopsBefore, controller.stopCalls)
        assertEquals(0, controller.loadCount)
        assertEquals(PlaybackError.QUEUE_UNPLAYABLE, coordinator.state.value.error)
    }

    @Test
    fun `shuffle is untouched by an unavailable-track skip`() {
        coordinator.setQueue(listOf(localA, ytmA, localB), startIndex = 0)
        advanceUntilIdle()

        controller.setShuffleModeEnabled(true)
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertTrue(controller.state.value.shuffleModeEnabled)
        assertEquals(localB, controller.loadedTrack)
    }

    @Test
    fun `repeat one keeps looping the same track and never walks into an unavailable item`() {
        coordinator.setQueue(listOf(localA, ytmA), startIndex = 0)
        advanceUntilIdle()
        controller.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ONE)
        advanceUntilIdle()

        controller.emitRepeatLoop(androidx.media3.common.Player.REPEAT_MODE_ONE)
        advanceUntilIdle()

        // REPEAT_ONE is the player's business; the queue must not advance, and the
        // unavailable item is never touched, so no error is raised at all.
        assertEquals(localA, controller.loadedTrack)
        assertEquals(1, controller.loadCount)
        assertNull(coordinator.state.value.error)
    }

    // ------------------------------------------------------------------
    // Queue management still works around unplayable items
    // ------------------------------------------------------------------

    @Test
    fun `jumping onto an unavailable track skips forward to the next playable one`() {
        coordinator.setQueue(listOf(localA, ytmA, localB), startIndex = 0)
        advanceUntilIdle()

        coordinator.jumpToQueueIndex(1) // straight onto the YTM item
        advanceUntilIdle()

        assertEquals(localB, controller.loadedTrack)
        assertEquals(2, coordinator.state.value.currentIndex)
        assertEquals(PlaybackError.UNAVAILABLE, coordinator.state.value.error)
    }

    @Test
    fun `removing the last playable item leaves a terminal unplayable queue`() {
        coordinator.setQueue(listOf(localA, ytmA), startIndex = 0)
        advanceUntilIdle()

        coordinator.removeQueueItem(0) // drop the only playable track
        advanceUntilIdle()

        assertEquals(PlaybackError.QUEUE_UNPLAYABLE, coordinator.state.value.error)
        assertNull(controller.loadedTrack)
        assertEquals(ytmA, coordinator.state.value.currentTrack)
    }

    // ------------------------------------------------------------------
    // The user-visible message (M20 Task 4)
    // ------------------------------------------------------------------

    /**
     * The terminal message is the whole of what the user learns, so it is
     * asserted rather than assumed. Two things matter: it names the QUEUE (the
     * actual outcome) rather than one item, and it does not blame a service —
     * Naudio only knows its own providers declined, which is what the response
     * to the user has to convey.
     */
    @Test
    fun `the terminal message names the queue and does not blame a service`() {
        val message = PlaybackError.QUEUE_UNPLAYABLE.message()

        assertTrue(message.isNotBlank())
        assertTrue(message.contains("queued", ignoreCase = true))
        // The provider-agnostic guarantee: no blame, no policy claim.
        listOf("YouTube", "blocked", "banned", "premium", "sign in", "login").forEach { forbidden ->
            assertFalse(
                "message must not mention '$forbidden': $message",
                message.contains(forbidden, ignoreCase = true),
            )
        }
    }

    /** Every error renders to a distinct, non-blank string — no silent cases. */
    @Test
    fun `every playback error renders a distinct non-blank message`() {
        val messages = PlaybackError.entries.map { it to it.message() }

        assertEquals(PlaybackError.entries.size, messages.size)
        messages.forEach { (error, message) ->
            assertTrue("$error has a blank message", message.isNotBlank())
        }
        assertEquals(
            "two errors share the same text, so the user cannot tell them apart",
            messages.size,
            messages.map { it.second }.toSet().size,
        )
    }
}