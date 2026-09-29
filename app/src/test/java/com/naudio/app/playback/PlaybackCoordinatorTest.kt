package com.naudio.app.playback

import com.naudio.core.database.entity.QueueEntity
import com.naudio.core.player.PlaybackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic tests for the M10 PlaybackCoordinator: queue semantics,
 * ENDED auto-advance, unplayable-track skipping, persisted position, and
 * startup restoration (no autoplay; Play loads the restored item).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private lateinit var controller: FakePlaybackController
    private lateinit var queueDao: FakeQueueDao
    private lateinit var trackDao: InMemoryTrackDao
    private lateinit var coordinator: PlaybackCoordinator

    private val localA = testTracks()[0]
    private val localB = testTracks()[1]
    private val localC = testTracks()[2]
    private val ytmB = ytmTrack()
    private val ytmC = ytmTrack("y2")

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

    private fun seedQueue(vararg tracks: com.naudio.core.model.Track, currentIndex: Int? = null) {
        queueDao.items.value = tracks.mapIndexed { index, track ->
            QueueEntity(
                orderIndex = index,
                providerId = track.providerId,
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                durationMs = track.durationMs,
            )
        }
        queueDao.state.value = currentIndex?.let {
            com.naudio.core.database.entity.QueueStateEntity(currentIndex = it)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // setQueue semantics
    // ------------------------------------------------------------------

    @Test
    fun `setQueue loads the track at the start index and persists it`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 1)
        advanceUntilIdle()

        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localB, coordinator.state.value.currentTrack)
        assertEquals(3, coordinator.state.value.queue.size)
        assertEquals(localB, controller.loadedTrack)
        // The whole queue and the position are persisted.
        assertEquals(3, queueDao.items.value.size)
        assertEquals(1, queueDao.state.value?.currentIndex)
        // Queue tracks are mirrored into the tracks table.
        assertEquals(3, trackDao.rows.size)
    }

    @Test
    fun `setQueue with an out-of-range start index is ignored`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 5)
        advanceUntilIdle()

        assertNull(coordinator.state.value.currentIndex)
        assertNull(controller.loadedTrack)
        assertEquals(0, queueDao.items.value.size)
    }

    @Test
    fun `setQueue with a negative start index is ignored`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = -1)
        advanceUntilIdle()

        assertNull(coordinator.state.value.currentIndex)
        assertNull(controller.loadedTrack)
    }

    @Test
    fun `setQueue with an empty list is ignored`() {
        coordinator.setQueue(emptyList(), startIndex = 0)
        advanceUntilIdle()

        assertTrue(coordinator.state.value.queue.isEmpty())
        assertNull(controller.loadedTrack)
        assertEquals(0, queueDao.items.value.size)
    }

    @Test
    fun `queue replacement discards the old queue`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()
        coordinator.setQueue(listOf(localC), startIndex = 0)
        advanceUntilIdle()

        assertEquals(listOf(localC), coordinator.state.value.queue)
        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(localC, controller.loadedTrack)
        assertEquals(1, queueDao.items.value.size)
    }

    // ------------------------------------------------------------------
    // skip next / previous + boundaries
    // ------------------------------------------------------------------

    @Test
    fun `skipToNext advances and resolves the next track`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 0)
        advanceUntilIdle()
        coordinator.skipToNext()
        advanceUntilIdle()

        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
        assertEquals(1, queueDao.state.value?.currentIndex)
    }

    @Test
    fun `skipToPrevious goes back and resolves the previous track`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 2)
        advanceUntilIdle()
        coordinator.skipToPrevious()
        advanceUntilIdle()

        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
    }

    @Test
    fun `skipToNext at the final item is a safe no-op`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 1)
        advanceUntilIdle()
        coordinator.skipToNext()
        advanceUntilIdle()

        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
        assertEquals(1, controller.loadCount)
    }

    @Test
    fun `skipToPrevious at the first item resumes the current track`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()
        coordinator.skipToPrevious()
        advanceUntilIdle()

        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(1, controller.playCalls)
        assertEquals(localA, controller.loadedTrack)
    }

    @Test
    fun `skip without a queue is a safe no-op`() {
        coordinator.skipToNext()
        coordinator.skipToPrevious()
        advanceUntilIdle()

        assertNull(coordinator.state.value.currentIndex)
        assertEquals(0, controller.loadCount)
    }

    // ------------------------------------------------------------------
    // auto-advance on ENDED
    // ------------------------------------------------------------------

    @Test
    fun `ENDED auto-advances to the next queue item`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
    }

    @Test
    fun `ENDED on the final item does not loop and keeps the final position`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 1)
        advanceUntilIdle()
        val loadsBefore = controller.loadCount
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(loadsBefore, controller.loadCount)
        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localB, coordinator.state.value.currentTrack)
        assertEquals(1, queueDao.state.value?.currentIndex)
    }

    @Test
    fun `distinct ENDED emissions do not double-advance`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.ENDED)
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
    }

    // ------------------------------------------------------------------
    // unplayable tracks
    // ------------------------------------------------------------------

    @Test
    fun `user selection of an unplayable YTM track surfaces unavailable and does not load`() {
        coordinator.setQueue(listOf(ytmB), startIndex = 0)
        advanceUntilIdle()

        assertEquals(PlaybackError.UNAVAILABLE, coordinator.state.value.error)
        assertNull(controller.loadedTrack)
        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(ytmB, coordinator.state.value.currentTrack)
    }

    @Test
    fun `auto-advance skips an unplayable YTM item and reaches the next playable one`() {
        coordinator.setQueue(listOf(localA, ytmB, localC), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.ENDED) // A ended -> try B (YTM)
        advanceUntilIdle()

        assertEquals(2, coordinator.state.value.currentIndex)
        assertEquals(localC, controller.loadedTrack)
        assertEquals(PlaybackError.UNAVAILABLE, coordinator.state.value.error)
    }

    @Test
    fun `skipToNext over an unplayable item reaches the next playable one`() {
        coordinator.setQueue(listOf(localA, ytmB, localC), startIndex = 0)
        advanceUntilIdle()
        coordinator.skipToNext()
        advanceUntilIdle()

        assertEquals(2, coordinator.state.value.currentIndex)
        assertEquals(localC, controller.loadedTrack)
    }

    @Test
    fun `all remaining items unplayable stops the queue safely`() {
        coordinator.setQueue(listOf(localA, ytmB), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(1, controller.loadCount)
        assertEquals(localA, controller.loadedTrack)
        assertEquals(PlaybackError.UNAVAILABLE, coordinator.state.value.error)
        // Settled (not advanced past) position is persisted.
        assertEquals(1, queueDao.state.value?.currentIndex)
    }

    @Test
    fun `skipError surfaces only once for consecutive unplayable items`() {
        coordinator.setQueue(listOf(localA, ytmB, ytmC, localC), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(localC, controller.loadedTrack)
        assertEquals(PlaybackError.UNAVAILABLE, coordinator.state.value.error)
    }

    // ------------------------------------------------------------------
    // persistence of queue tracks (favorite-state preservation)
    // ------------------------------------------------------------------

    @Test
    fun `queue persistence preserves existing favorite state of known tracks`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()
        // Favoriting happens through the favorites repository on the same DAO.
        val favorites = testFavoritesRepository(trackDao)
        testScope.launch { favorites.toggleFavorite(localA, true) }
        advanceUntilIdle()

        coordinator.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()

        val rowA = trackDao.rows[localA.providerId to localA.id]!!
        // A re-persisted queue row must carry the favorite flag over.
        assertTrue(rowA.isFavorite)
    }

    // ------------------------------------------------------------------
    // startup restoration
    // ------------------------------------------------------------------

    @Test
    fun `restore populates queue and index without loading or playing`() {
        seedQueue(localA, localB, localC, currentIndex = 1)
        coordinator = newCoordinator()
        advanceUntilIdle()

        assertEquals(listOf(localA, localB, localC), coordinator.state.value.queue)
        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localB, coordinator.state.value.currentTrack)
        // Restoration never touches the player.
        assertEquals(0, controller.loadCount)
        assertEquals(0, controller.playCalls)
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)
        assertNull(controller.loadedTrack)
    }

    @Test
    fun `restore with an out-of-range persisted index clamps to no position`() {
        seedQueue(localA, localB, currentIndex = 9)
        coordinator = newCoordinator()
        advanceUntilIdle()

        assertEquals(2, coordinator.state.value.queue.size)
        assertNull(coordinator.state.value.currentIndex)
        assertEquals(0, controller.loadCount)
    }

    @Test
    fun `restore with an empty persisted queue restores nothing`() {
        seedQueue(currentIndex = null)
        coordinator = newCoordinator()
        advanceUntilIdle()

        assertTrue(coordinator.state.value.queue.isEmpty())
        assertNull(coordinator.state.value.currentIndex)
    }

    @Test
    fun `pressing play after restore resolves and loads the restored current track`() {
        seedQueue(localA, localB, localC, currentIndex = 1)
        coordinator = newCoordinator()
        advanceUntilIdle()

        coordinator.onTogglePlayPause()
        advanceUntilIdle()

        assertEquals(localB, controller.loadedTrack)
        assertEquals(1, controller.loadCount)
        assertEquals(PlaybackStatus.READY, controller.state.value.status)
    }

    @Test
    fun `skipToNext after restore advances from the restored position without autoplay`() {
        seedQueue(localA, localB, localC, currentIndex = 0)
        coordinator = newCoordinator()
        advanceUntilIdle()

        coordinator.skipToNext()
        advanceUntilIdle()

        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
        // Restore itself never played anything.
        assertEquals(0, controller.playCalls)
    }

    @Test
    fun `restored queue with stale ENDED status does not autoplay`() {
        seedQueue(localA, localB, currentIndex = 0)
        // Simulate the controller still reporting ENDED from the previous
        // process (e.g. a recycled service session): restore must not advance.
        controller.emitStatus(PlaybackStatus.ENDED)
        coordinator = newCoordinator()
        advanceUntilIdle()

        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(0, controller.loadCount)
        assertEquals(0, controller.playCalls)
        assertNull(coordinator.state.value.error)
    }

    @Test
    fun `user action racing the restore wins over stale persisted state`() {
        seedQueue(localA, localB, localC, currentIndex = 0)
        coordinator = newCoordinator()
        // The user selects a track before the restore read completes.
        coordinator.setQueue(listOf(localC), startIndex = 0)
        advanceUntilIdle()

        assertEquals(listOf(localC), coordinator.state.value.queue)
        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(localC, controller.loadedTrack)
        assertEquals(1, queueDao.items.value.size)
    }
}
