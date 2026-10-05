package com.naudio.app.ui

import com.naudio.app.playback.FakePlaybackController
import com.naudio.app.playback.PlaybackError
import com.naudio.app.playback.testFavoritesRepository
import com.naudio.app.playback.testLibraryRepository
import com.naudio.app.playback.testQueueRepository
import com.naudio.app.playback.testTracks
import com.naudio.app.playback.ytmTrack
import com.naudio.core.player.PlaybackStatus
import com.naudio.data.repository.FavoritesRepository
import com.naudio.data.repository.LibraryRepository
import com.naudio.data.repository.QueueRepository
import com.naudio.data.repository.TrackKey
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
 * Regression tests for the M10 PlaybackViewModel facade: the M9 queue
 * semantics (setQueue/skip/replace, ENDED auto-advance, unplayable skipping,
 * favorites) must be preserved end-to-end through the coordinator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private lateinit var controller: FakePlaybackController
    private lateinit var repository: LibraryRepository
    private lateinit var favorites: FavoritesRepository
    private lateinit var queueRepository: QueueRepository
    private lateinit var viewModel: PlaybackViewModel

    private val localA = testTracks()[0]
    private val localB = testTracks()[1]
    private val localC = testTracks()[2]
    private val ytmB = ytmTrack()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        controller = FakePlaybackController()
        repository = testLibraryRepository()
        val trackDao = com.naudio.app.playback.InMemoryTrackDao()
        favorites = testFavoritesRepository(trackDao)
        queueRepository = testQueueRepository(
            trackDao,
            com.naudio.app.playback.FakeQueueDao(),
        )
        viewModel = PlaybackViewModel(controller, repository, favorites, queueRepository)
        testScope.advanceUntilIdle()
    }

    private fun advanceUntilIdle() = testScope.advanceUntilIdle()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // setQueue semantics
    // ------------------------------------------------------------------

    @Test
    fun `setQueue loads the track at the start index and plays it`() {
        viewModel.setQueue(listOf(localA, localB, localC), startIndex = 1)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(localB, viewModel.uiState.value.currentTrack)
        assertEquals(3, viewModel.uiState.value.queue.size)
        assertEquals(localB, controller.loadedTrack)
    }

    @Test
    fun `setQueue with an out-of-range start index is ignored`() {
        viewModel.setQueue(listOf(localA, localB), startIndex = 5)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.currentIndex)
        assertNull(controller.loadedTrack)
    }

    @Test
    fun `setQueue with a negative start index is ignored`() {
        viewModel.setQueue(listOf(localA, localB), startIndex = -1)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.currentIndex)
        assertNull(controller.loadedTrack)
    }

    @Test
    fun `setQueue with an empty list is ignored`() {
        viewModel.setQueue(emptyList(), startIndex = 0)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.queue.isEmpty())
        assertNull(controller.loadedTrack)
    }

    @Test
    fun `queue replacement discards the old queue`() {
        viewModel.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()
        viewModel.setQueue(listOf(localC), startIndex = 0)
        advanceUntilIdle()

        assertEquals(listOf(localC), viewModel.uiState.value.queue)
        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals(localC, controller.loadedTrack)
    }

    @Test
    fun `onTrackSelected plays a single-item queue`() {
        viewModel.onTrackSelected(localA)
        advanceUntilIdle()

        assertEquals(listOf(localA), viewModel.uiState.value.queue)
        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals(localA, controller.loadedTrack)
    }

    // ------------------------------------------------------------------
    // skip next / previous + boundaries
    // ------------------------------------------------------------------

    @Test
    fun `skipToNext advances and resolves the next track`() {
        viewModel.setQueue(listOf(localA, localB, localC), startIndex = 0)
        advanceUntilIdle()
        viewModel.skipToNext()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
    }

    @Test
    fun `skipToPrevious goes back and resolves the previous track`() {
        viewModel.setQueue(listOf(localA, localB, localC), startIndex = 2)
        advanceUntilIdle()
        viewModel.skipToPrevious()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
    }

    @Test
    fun `skipToNext at the final item is a safe no-op`() {
        viewModel.setQueue(listOf(localA, localB), startIndex = 1)
        advanceUntilIdle()
        viewModel.skipToNext()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
        assertEquals(1, controller.loadCount)
    }

    @Test
    fun `skipToPrevious at the first item resumes the current track`() {
        viewModel.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()
        viewModel.skipToPrevious()
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals(1, controller.playCalls)
        assertEquals(localA, controller.loadedTrack)
    }

    @Test
    fun `skip without a queue is a safe no-op`() {
        viewModel.skipToNext()
        viewModel.skipToPrevious()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.currentIndex)
        assertEquals(0, controller.loadCount)
    }

    // ------------------------------------------------------------------
    // auto-advance on ENDED
    // ------------------------------------------------------------------

    @Test
    fun `ENDED auto-advances to the next queue item`() {
        viewModel.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
    }

    @Test
    fun `ENDED on the final item does not loop and keeps the final position`() {
        viewModel.setQueue(listOf(localA, localB), startIndex = 1)
        advanceUntilIdle()
        val loadsBefore = controller.loadCount
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(loadsBefore, controller.loadCount)
        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(localB, viewModel.uiState.value.currentTrack)
    }

    @Test
    fun `distinct ENDED emissions do not double-advance`() {
        viewModel.setQueue(listOf(localA, localB, localC), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.ENDED)
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
    }

    // ------------------------------------------------------------------
    // unplayable tracks
    // ------------------------------------------------------------------

    @Test
    fun `user selection of an unplayable YTM track reports the queue as unplayable`() {
        viewModel.onTrackSelected(ytmB)
        advanceUntilIdle()

        // M20: terminal, because a one-item queue has nothing left to skip to.
        assertEquals(PlaybackError.QUEUE_UNPLAYABLE, viewModel.playbackError.value)
        assertNull(controller.loadedTrack)
    }

    @Test
    fun `auto-advance skips an unplayable YTM item and reaches the next playable one`() {
        viewModel.setQueue(listOf(localA, ytmB, localC), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.ENDED) // A ended -> try B (YTM)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.currentIndex)
        assertEquals(localC, controller.loadedTrack)
        assertEquals(PlaybackError.UNAVAILABLE, viewModel.playbackError.value)
    }

    @Test
    fun `skipToNext over an unplayable item reaches the next playable one`() {
        viewModel.setQueue(listOf(localA, ytmB, localC), startIndex = 0)
        advanceUntilIdle()
        viewModel.skipToNext()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.currentIndex)
        assertEquals(localC, controller.loadedTrack)
    }

    @Test
    fun `queue of only unplayable items stops gracefully without loading`() {
        viewModel.onTrackSelected(ytmB)
        advanceUntilIdle()

        assertEquals(PlaybackError.QUEUE_UNPLAYABLE, viewModel.playbackError.value)
        assertNull(controller.loadedTrack)
        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals(ytmB, viewModel.uiState.value.currentTrack)
    }

    @Test
    fun `all remaining items unplayable stops the player and reports a terminal error`() {
        viewModel.setQueue(listOf(localA, ytmB), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        // M20: the exhausted run stops the player so nothing stale is left
        // loaded or advertised, and reports a terminal (not per-item) error.
        assertEquals(1, controller.loadCount)
        assertEquals(1, controller.stopCalls)
        assertNull(controller.loadedTrack)
        assertEquals(PlaybackError.QUEUE_UNPLAYABLE, viewModel.playbackError.value)
    }

    @Test
    fun `skipError surfaces only once for consecutive unplayable items`() {
        val ytmC = ytmTrack("y2")
        viewModel.setQueue(listOf(localA, ytmB, ytmC, localC), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(localC, controller.loadedTrack)
        assertEquals(PlaybackError.UNAVAILABLE, viewModel.playbackError.value)
    }

    // ------------------------------------------------------------------
    // favorites integration
    // ------------------------------------------------------------------

    @Test
    fun `favorite state of the current track is observed`() {
        viewModel.setQueue(listOf(localA), startIndex = 0)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isFavorite)

        viewModel.onToggleFavorite()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isFavorite)

        viewModel.onToggleFavorite()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isFavorite)
    }

    @Test
    fun `favoriting works for an unplayable YTM track`() {
        viewModel.onTrackSelected(ytmB) // unplayable -> error, but current index set
        advanceUntilIdle()

        viewModel.onToggleFavorite()
        advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.favoriteIds.contains(TrackKey(ytmB)),
        )
    }
}
