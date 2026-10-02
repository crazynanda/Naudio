package com.naudio.app.playback

import androidx.media3.common.Player
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
import org.junit.Before
import org.junit.Test

/**
 * M16: queue behaviour under Media3's repeat modes.
 *
 * Naudio's queue lives in this coordinator while Media3 holds a single-item
 * timeline, so a repeating player never reports ENDED. These tests pin the
 * agreed semantics:
 *
 *  - REPEAT_MODE_OFF  : A → B → C, then stop (existing ENDED path, untouched)
 *  - REPEAT_MODE_ONE  : the player loops the current item; the queue does not move
 *  - REPEAT_MODE_ALL  : the coordinator advances on the player's repeat
 *                       transition and wraps from the last item to the first
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackModeCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private lateinit var controller: FakePlaybackController
    private lateinit var coordinator: PlaybackCoordinator

    private val trackA = testTracks()[0]
    private val trackB = testTracks()[1]
    private val trackC = testTracks()[2]

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        controller = FakePlaybackController()
        val trackDao = InMemoryTrackDao()
        coordinator = PlaybackCoordinator(
            playbackController = controller,
            libraryRepository = testLibraryRepository(),
            queueRepository = testQueueRepository(trackDao, FakeQueueDao()),
            scope = testScope,
        )
        testScope.advanceUntilIdle()
    }

    private fun advanceUntilIdle() = testScope.advanceUntilIdle()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // REPEAT_MODE_OFF — existing behaviour preserved
    // ------------------------------------------------------------------

    @Test
    fun `repeat off preserves the existing ENDED auto-advance`() {
        coordinator.setQueue(listOf(trackA, trackB, trackC), startIndex = 0)
        advanceUntilIdle()

        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(trackB, coordinator.state.value.currentTrack)
    }

    @Test
    fun `repeat off does not advance on a player repeat loop`() {
        // With repeat off Media3 reports ENDED rather than looping, so a
        // stray repeat transition must not move the queue.
        controller.setRepeatMode(Player.REPEAT_MODE_OFF)
        coordinator.setQueue(listOf(trackA, trackB, trackC), startIndex = 0)
        advanceUntilIdle()

        controller.emitRepeatLoop(Player.REPEAT_MODE_OFF)
        advanceUntilIdle()

        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(trackA, coordinator.state.value.currentTrack)
    }

    @Test
    fun `repeat off still stops at the last item`() {
        controller.setRepeatMode(Player.REPEAT_MODE_OFF)
        coordinator.setQueue(listOf(trackA, trackB), startIndex = 1)
        advanceUntilIdle()

        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(1, coordinator.state.value.currentIndex)
    }

    // ------------------------------------------------------------------
    // REPEAT_MODE_ONE — Media3 handles it natively
    // ------------------------------------------------------------------

    @Test
    fun `repeat one does not advance the queue on a player loop`() {
        controller.setRepeatMode(Player.REPEAT_MODE_ONE)
        coordinator.setQueue(listOf(trackA, trackB, trackC), startIndex = 0)
        advanceUntilIdle()

        controller.emitRepeatLoop(Player.REPEAT_MODE_ONE)
        advanceUntilIdle()
        controller.emitRepeatLoop(Player.REPEAT_MODE_ONE)
        advanceUntilIdle()

        // Media3 keeps replaying the same item; the queue must stay put.
        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(trackA, coordinator.state.value.currentTrack)
    }

    @Test
    fun `repeat one still allows an explicit user skip`() {
        controller.setRepeatMode(Player.REPEAT_MODE_ONE)
        coordinator.setQueue(listOf(trackA, trackB), startIndex = 0)
        advanceUntilIdle()

        coordinator.skipToNext()
        advanceUntilIdle()

        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(trackB, coordinator.state.value.currentTrack)
    }

    // ------------------------------------------------------------------
    // REPEAT_MODE_ALL — the coordinator advances and wraps
    // ------------------------------------------------------------------

    @Test
    fun `repeat all advances to the next queue item on a player loop`() {
        controller.setRepeatMode(Player.REPEAT_MODE_ALL)
        coordinator.setQueue(listOf(trackA, trackB, trackC), startIndex = 0)
        advanceUntilIdle()

        controller.emitRepeatLoop(Player.REPEAT_MODE_ALL)
        advanceUntilIdle()

        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(trackB, coordinator.state.value.currentTrack)
        assertEquals(trackB, controller.loadedTrack)
    }

    @Test
    fun `repeat all walks the whole queue`() {
        controller.setRepeatMode(Player.REPEAT_MODE_ALL)
        coordinator.setQueue(listOf(trackA, trackB, trackC), startIndex = 0)
        advanceUntilIdle()

        controller.emitRepeatLoop(Player.REPEAT_MODE_ALL)
        advanceUntilIdle()
        assertEquals(1, coordinator.state.value.currentIndex)

        controller.emitRepeatLoop(Player.REPEAT_MODE_ALL)
        advanceUntilIdle()
        assertEquals(2, coordinator.state.value.currentIndex)
        assertEquals(trackC, coordinator.state.value.currentTrack)
    }

    @Test
    fun `repeat all wraps from the last item back to the first`() {
        controller.setRepeatMode(Player.REPEAT_MODE_ALL)
        coordinator.setQueue(listOf(trackA, trackB, trackC), startIndex = 2)
        advanceUntilIdle()
        assertEquals(trackC, controller.loadedTrack)

        controller.emitRepeatLoop(Player.REPEAT_MODE_ALL)
        advanceUntilIdle()

        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(trackA, coordinator.state.value.currentTrack)
        assertEquals(trackA, controller.loadedTrack)
    }

    @Test
    fun `repeat all on a single item queue does not break`() {
        controller.setRepeatMode(Player.REPEAT_MODE_ALL)
        coordinator.setQueue(listOf(trackA), startIndex = 0)
        advanceUntilIdle()

        repeat(3) {
            controller.emitRepeatLoop(Player.REPEAT_MODE_ALL)
            advanceUntilIdle()
        }

        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(trackA, coordinator.state.value.currentTrack)
        assertEquals(trackA, controller.loadedTrack)
    }

    @Test
    fun `repeat all advances only once per player loop`() {
        controller.setRepeatMode(Player.REPEAT_MODE_ALL)
        coordinator.setQueue(listOf(trackA, trackB, trackC), startIndex = 0)
        advanceUntilIdle()
        val loadsBefore = controller.loadCount

        controller.emitRepeatLoop(Player.REPEAT_MODE_ALL)
        advanceUntilIdle()

        assertEquals(1, controller.loadCount - loadsBefore)
        assertEquals(1, coordinator.state.value.currentIndex)
    }

    @Test
    fun `turning repeat off restores the ENDED advance semantics`() {
        controller.setRepeatMode(Player.REPEAT_MODE_ALL)
        coordinator.setQueue(listOf(trackA, trackB, trackC), startIndex = 0)
        advanceUntilIdle()

        controller.emitRepeatLoop(Player.REPEAT_MODE_ALL)
        advanceUntilIdle()
        assertEquals(1, coordinator.state.value.currentIndex)

        controller.setRepeatMode(Player.REPEAT_MODE_OFF)
        advanceUntilIdle()

        controller.emitRepeatLoop(Player.REPEAT_MODE_OFF)
        advanceUntilIdle()
        assertEquals(1, coordinator.state.value.currentIndex)

        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()
        assertEquals(2, coordinator.state.value.currentIndex)
    }

    @Test
    fun `selecting repeat all is not itself a loop event`() {
        controller.setRepeatMode(Player.REPEAT_MODE_OFF)
        coordinator.setQueue(listOf(trackA, trackB, trackC), startIndex = 0)
        advanceUntilIdle()
        val loadsBefore = controller.loadCount

        // Changing the mode produces a new (loopCount, repeatMode) emission
        // whose loop count is UNCHANGED. Neither this first observation of ALL
        // nor a mode switch may be mistaken for the player having looped.
        controller.setRepeatMode(Player.REPEAT_MODE_ALL)
        advanceUntilIdle()

        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(trackA, controller.loadedTrack)
        assertEquals(loadsBefore, controller.loadCount)
    }

    @Test
    fun `switching from repeat all to repeat one stops queue advancing`() {
        controller.setRepeatMode(Player.REPEAT_MODE_ALL)
        coordinator.setQueue(listOf(trackA, trackB, trackC), startIndex = 0)
        advanceUntilIdle()

        // REPEAT_ALL genuinely advances the queue...
        controller.emitRepeatLoop(Player.REPEAT_MODE_ALL)
        advanceUntilIdle()
        assertEquals(1, coordinator.state.value.currentIndex)

        // ...but after switching to REPEAT_ONE the player loops natively and
        // the coordinator must not keep moving the queue.
        controller.setRepeatMode(Player.REPEAT_MODE_ONE)
        advanceUntilIdle()
        controller.emitRepeatLoop(Player.REPEAT_MODE_ONE)
        advanceUntilIdle()

        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(trackB, controller.loadedTrack)
    }

    @Test
    fun `repeat all does not disturb queue persistence contents`() {
        controller.setRepeatMode(Player.REPEAT_MODE_ALL)
        coordinator.setQueue(listOf(trackA, trackB, trackC), startIndex = 0)
        advanceUntilIdle()

        controller.emitRepeatLoop(Player.REPEAT_MODE_ALL)
        advanceUntilIdle()

        // The stored order is never rewritten by Media3 shuffle/repeat.
        assertEquals(listOf(trackA, trackB, trackC), coordinator.state.value.queue)
    }
}
