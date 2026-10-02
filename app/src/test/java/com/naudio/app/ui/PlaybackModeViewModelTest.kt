package com.naudio.app.ui

import androidx.media3.common.Player
import com.naudio.app.playback.FakePlaybackController
import com.naudio.app.playback.InMemoryTrackDao
import com.naudio.app.playback.testFavoritesRepository
import com.naudio.app.playback.testLibraryRepository
import com.naudio.app.playback.testQueueRepository
import com.naudio.app.playback.testTracks
import com.naudio.data.repository.FavoritesRepository
import com.naudio.data.repository.LibraryRepository
import com.naudio.data.repository.QueueRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M16: shuffle/repeat intents on [PlaybackViewModel].
 *
 * The ViewModel owns no shuffle/repeat state: each intent sends a command to
 * the controller, and the assertions read the resulting [com.naudio.core.player.PlayerState]
 * back out of the controller — the same path the UI observes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackModeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private lateinit var controller: FakePlaybackController
    private lateinit var repository: LibraryRepository
    private lateinit var favorites: FavoritesRepository
    private lateinit var queueRepository: QueueRepository
    private lateinit var viewModel: PlaybackViewModel

    private val trackA = testTracks()[0]
    private val trackB = testTracks()[1]
    private val trackC = testTracks()[2]

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        controller = FakePlaybackController()
        repository = testLibraryRepository()
        val trackDao = InMemoryTrackDao()
        favorites = testFavoritesRepository(trackDao)
        queueRepository = testQueueRepository(trackDao, com.naudio.app.playback.FakeQueueDao())
        viewModel = PlaybackViewModel(controller, repository, favorites, queueRepository)
        testScope.advanceUntilIdle()
    }

    private fun advanceUntilIdle() = testScope.advanceUntilIdle()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // shuffle
    // ------------------------------------------------------------------

    @Test
    fun `shuffle starts disabled`() {
        assertFalse(viewModel.playbackState.value.shuffleModeEnabled)
    }

    @Test
    fun `toggle enables shuffle and it propagates through player state`() {
        viewModel.onToggleShuffle()
        advanceUntilIdle()

        assertEquals(listOf(true), controller.setShuffleCalls)
        assertTrue(viewModel.playbackState.value.shuffleModeEnabled)
    }

    @Test
    fun `toggling again disables shuffle`() {
        viewModel.onToggleShuffle()
        advanceUntilIdle()
        viewModel.onToggleShuffle()
        advanceUntilIdle()

        assertEquals(listOf(true, false), controller.setShuffleCalls)
        assertFalse(viewModel.playbackState.value.shuffleModeEnabled)
    }

    @Test
    fun `repeated shuffle toggling alternates and ends on the final value`() {
        repeat(5) {
            viewModel.onToggleShuffle()
            advanceUntilIdle()
        }

        assertEquals(listOf(true, false, true, false, true), controller.setShuffleCalls)
        assertTrue(viewModel.playbackState.value.shuffleModeEnabled)
    }

    @Test
    fun `shuffle state change made by the player is reflected in player state`() {
        // Simulates a change originating from Android Auto: the player is the
        // source of truth, and the mobile UI observes it without any intent.
        controller.setShuffleModeEnabled(true)
        advanceUntilIdle()

        assertTrue(viewModel.playbackState.value.shuffleModeEnabled)
    }

    // ------------------------------------------------------------------
    // repeat
    // ------------------------------------------------------------------

    @Test
    fun `repeat starts off`() {
        assertEquals(Player.REPEAT_MODE_OFF, viewModel.playbackState.value.repeatMode)
        assertTrue(viewModel.playbackState.value.isRepeatOff)
    }

    @Test
    fun `cycle goes OFF to ALL`() {
        viewModel.onCycleRepeatMode()
        advanceUntilIdle()

        assertEquals(listOf(Player.REPEAT_MODE_ALL), controller.setRepeatCalls)
        assertEquals(Player.REPEAT_MODE_ALL, viewModel.playbackState.value.repeatMode)
        assertTrue(viewModel.playbackState.value.isRepeatAll)
    }

    @Test
    fun `cycle goes ALL to ONE`() {
        viewModel.onCycleRepeatMode()
        advanceUntilIdle()
        viewModel.onCycleRepeatMode()
        advanceUntilIdle()

        assertEquals(
            listOf(Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE),
            controller.setRepeatCalls,
        )
        assertEquals(Player.REPEAT_MODE_ONE, viewModel.playbackState.value.repeatMode)
        assertTrue(viewModel.playbackState.value.isRepeatOne)
    }

    @Test
    fun `cycle goes ONE to OFF`() {
        repeat(3) {
            viewModel.onCycleRepeatMode()
            advanceUntilIdle()
        }

        assertEquals(
            listOf(
                Player.REPEAT_MODE_ALL,
                Player.REPEAT_MODE_ONE,
                Player.REPEAT_MODE_OFF,
            ),
            controller.setRepeatCalls,
        )
        assertEquals(Player.REPEAT_MODE_OFF, viewModel.playbackState.value.repeatMode)
        assertTrue(viewModel.playbackState.value.isRepeatOff)
    }

    @Test
    fun `repeated cycling returns to off`() {
        repeat(3) {
            viewModel.onCycleRepeatMode()
            advanceUntilIdle()
        }
        assertTrue(viewModel.playbackState.value.isRepeatOff)

        viewModel.onCycleRepeatMode()
        advanceUntilIdle()
        assertTrue(viewModel.playbackState.value.isRepeatAll)
    }

    @Test
    fun `repeat change made by the player is reflected in player state`() {
        // Auto-driven change: reflected without any mobile intent.
        controller.setRepeatMode(Player.REPEAT_MODE_ONE)
        advanceUntilIdle()

        assertEquals(Player.REPEAT_MODE_ONE, viewModel.playbackState.value.repeatMode)
        assertTrue(viewModel.playbackState.value.isRepeatOne)
    }

    @Test
    fun `cycle continues from a player driven repeat mode`() {
        controller.setRepeatMode(Player.REPEAT_MODE_ALL)
        advanceUntilIdle()

        viewModel.onCycleRepeatMode()
        advanceUntilIdle()

        assertEquals(Player.REPEAT_MODE_ONE, viewModel.playbackState.value.repeatMode)
    }

    // ------------------------------------------------------------------
    // interaction with the existing queue behaviour
    // ------------------------------------------------------------------

    @Test
    fun `changing playback modes does not disturb the queue`() {
        viewModel.setQueue(listOf(trackA, trackB, trackC), startIndex = 1)
        advanceUntilIdle()

        viewModel.onToggleShuffle()
        viewModel.onCycleRepeatMode()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(trackB, viewModel.uiState.value.currentTrack)
        assertEquals(3, viewModel.uiState.value.queue.size)
        assertTrue(viewModel.playbackState.value.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, viewModel.playbackState.value.repeatMode)
    }

    @Test
    fun `skip still works while repeat all is selected`() {
        viewModel.setQueue(listOf(trackA, trackB, trackC), startIndex = 0)
        viewModel.onCycleRepeatMode() // ALL
        advanceUntilIdle()

        viewModel.skipToNext()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(trackB, viewModel.uiState.value.currentTrack)
    }

    @Test
    fun `play pause still works while shuffle is enabled`() {
        viewModel.setQueue(listOf(trackA, trackB), startIndex = 0)
        viewModel.onToggleShuffle()
        advanceUntilIdle()
        val pauseCallsBefore = controller.pauseCalls

        viewModel.onTogglePlayPause()
        advanceUntilIdle()

        // The play/pause intent still reaches the controller, and toggling
        // playback does not disturb the shuffle state or the queue.
        assertEquals(pauseCallsBefore + 1, controller.pauseCalls)
        assertTrue(viewModel.playbackState.value.shuffleModeEnabled)
        assertEquals(0, viewModel.uiState.value.currentIndex)
    }

    // ------------------------------------------------------------------
    // UI label rendering (state -> label, no UI state of its own)
    // ------------------------------------------------------------------

    @Test
    fun `repeat label distinguishes the three modes`() {
        assertEquals("Repeat", repeatLabel(Player.REPEAT_MODE_OFF))
        assertEquals("Repeat All", repeatLabel(Player.REPEAT_MODE_ALL))
        assertEquals("Repeat One", repeatLabel(Player.REPEAT_MODE_ONE))
    }
}
