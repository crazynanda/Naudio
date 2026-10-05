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
    fun `user selection of an unplayable YTM track reports the queue as unplayable`() {
        coordinator.setQueue(listOf(ytmB), startIndex = 0)
        advanceUntilIdle()

        // M20: a single-item queue that cannot play is the terminal case, not a
        // per-item skip — there is nothing left to skip to.
        assertEquals(PlaybackError.QUEUE_UNPLAYABLE, coordinator.state.value.error)
        assertNull(controller.loadedTrack)
        assertEquals(0, coordinator.state.value.currentIndex)
        // The queue still points at the item so the UI can show/favorite it.
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
    fun `all remaining items unplayable stops the player and reports a terminal error`() {
        coordinator.setQueue(listOf(localA, ytmB), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        // M20: A ends, B has no source — the run exhausts the queue, so the
        // player is stopped (no stale A still playing/advertised) and the
        // failure is terminal rather than a one-off skip note.
        assertEquals(1, controller.loadCount)
        assertEquals(1, controller.stopCalls)
        assertNull(controller.loadedTrack)
        assertNull(controller.state.value.track)
        assertEquals(PlaybackError.QUEUE_UNPLAYABLE, coordinator.state.value.error)
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

    // ------------------------------------------------------------------
    // M11: jumpToQueueIndex
    // ------------------------------------------------------------------

    @Test
    fun `jumpToQueueIndex resolves and loads the selected track`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 0)
        advanceUntilIdle()

        coordinator.jumpToQueueIndex(2)
        advanceUntilIdle()

        assertEquals(2, coordinator.state.value.currentIndex)
        assertEquals(localC, controller.loadedTrack)
        assertEquals(2, controller.loadCount)
        assertEquals(2, queueDao.state.value?.currentIndex)
    }

    @Test
    fun `invalid jumpToQueueIndex is a no-op`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()
        val loadsBefore = controller.loadCount

        coordinator.jumpToQueueIndex(-1)
        coordinator.jumpToQueueIndex(2)
        coordinator.jumpToQueueIndex(99)
        advanceUntilIdle()

        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(localA, controller.loadedTrack)
        assertEquals(loadsBefore, controller.loadCount)
    }

    @Test
    fun `jumping to the current index does not unnecessarily reload`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 1)
        advanceUntilIdle()
        val loadsBefore = controller.loadCount

        coordinator.jumpToQueueIndex(1)
        advanceUntilIdle()

        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(loadsBefore, controller.loadCount)
        // Playback resumes (the current item is ensured to be playing).
        assertEquals(1, controller.playCalls)
    }

    @Test
    fun `jump from an empty queue is a no-op`() {
        coordinator.jumpToQueueIndex(0)
        advanceUntilIdle()

        assertNull(coordinator.state.value.currentIndex)
        assertEquals(0, controller.loadCount)
    }

    // ------------------------------------------------------------------
    // M11: removeQueueItem — non-current removal never restarts playback
    // ------------------------------------------------------------------

    @Test
    fun `removing an item before the current index decrements the index`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 2)
        advanceUntilIdle()

        coordinator.removeQueueItem(0)
        advanceUntilIdle()

        assertEquals(listOf(localB, localC), coordinator.state.value.queue)
        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localC, coordinator.state.value.currentTrack)
        assertEquals(2, queueDao.items.value.size)
        assertEquals(1, queueDao.state.value?.currentIndex)
    }

    @Test
    fun `removing an item before the current index does not restart playback`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 2)
        advanceUntilIdle()
        val loadsBefore = controller.loadCount

        coordinator.removeQueueItem(0)
        advanceUntilIdle()

        assertEquals(loadsBefore, controller.loadCount)
        assertEquals(localC, controller.loadedTrack)
    }

    @Test
    fun `removing an item after the current index preserves the index`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 1)
        advanceUntilIdle()

        coordinator.removeQueueItem(2)
        advanceUntilIdle()

        assertEquals(listOf(localA, localB), coordinator.state.value.queue)
        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localB, coordinator.state.value.currentTrack)
        assertEquals(1, queueDao.state.value?.currentIndex)
    }

    @Test
    fun `removing an item after the current index does not restart playback`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 1)
        advanceUntilIdle()
        val loadsBefore = controller.loadCount

        coordinator.removeQueueItem(2)
        advanceUntilIdle()

        assertEquals(loadsBefore, controller.loadCount)
        assertEquals(localB, controller.loadedTrack)
    }

    // ------------------------------------------------------------------
    // M11: removeQueueItem — current-item removal
    // ------------------------------------------------------------------

    @Test
    fun `removing the current item advances to the next item and loads it exactly once`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 1)
        advanceUntilIdle()

        coordinator.removeQueueItem(1) // remove B while it plays
        advanceUntilIdle()

        assertEquals(listOf(localA, localC), coordinator.state.value.queue)
        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localC, coordinator.state.value.currentTrack)
        // Exactly one new load: B -> C, never B again, never a double C.
        assertEquals(2, controller.loadCount)
        assertEquals(localC, controller.loadedTrack)
        assertEquals(1, queueDao.state.value?.currentIndex)
    }

    @Test
    fun `removing the current item skips an unplayable successor`() {
        coordinator.setQueue(listOf(localA, ytmB, localC), startIndex = 0)
        advanceUntilIdle()

        coordinator.removeQueueItem(0) // remove A -> YTM must be skipped
        advanceUntilIdle()

        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localC, controller.loadedTrack)
        assertEquals(2, controller.loadCount)
    }

    @Test
    fun `removing the final current item falls back to the previous item`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 1)
        advanceUntilIdle()

        coordinator.removeQueueItem(1) // remove B (last)
        advanceUntilIdle()

        assertEquals(listOf(localA), coordinator.state.value.queue)
        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(localA, controller.loadedTrack)
        assertEquals(2, controller.loadCount)
        assertEquals(0, queueDao.state.value?.currentIndex)
    }

    @Test
    fun `removing the only item produces an empty no-position state and stops`() {
        coordinator.setQueue(listOf(localA), startIndex = 0)
        advanceUntilIdle()

        coordinator.removeQueueItem(0)
        advanceUntilIdle()

        assertTrue(coordinator.state.value.queue.isEmpty())
        assertNull(coordinator.state.value.currentIndex)
        assertNull(coordinator.state.value.currentTrack)
        // Nothing persisted: no rows, no position.
        assertTrue(queueDao.items.value.isEmpty())
        assertNull(queueDao.state.value?.currentIndex)
        // The session auto-advance gate is closed: a stray ENDED cannot
        // resurrect playback.
        val loadsBefore = controller.loadCount
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()
        assertEquals(loadsBefore, controller.loadCount)
    }

    @Test
    fun `invalid removal is a no-op`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()
        val loadsBefore = controller.loadCount

        coordinator.removeQueueItem(-1)
        coordinator.removeQueueItem(2)
        coordinator.removeQueueItem(99)
        advanceUntilIdle()

        assertEquals(listOf(localA, localB), coordinator.state.value.queue)
        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(loadsBefore, controller.loadCount)
        assertEquals(2, queueDao.items.value.size)
    }

    // ------------------------------------------------------------------
    // M11: persistence consistency under rapid mutations
    // ------------------------------------------------------------------

    @Test
    fun `rapid queue mutations leave Room consistent with memory`() {
        val localD = localA.copy(id = "d1", title = "D")
        coordinator.setQueue(listOf(localA, localB, localC, localD), startIndex = 2)
        advanceUntilIdle()

        // remove -> remove -> jump -> remove with no idle advance in between.
        coordinator.removeQueueItem(0)
        coordinator.removeQueueItem(0)
        coordinator.jumpToQueueIndex(1)
        coordinator.removeQueueItem(0)
        advanceUntilIdle()

        val state = coordinator.state.value
        assertEquals(1, state.queue.size)
        assertEquals(0, state.currentIndex)
        // Persisted queue/index combination matches the in-memory state.
        assertEquals(1, queueDao.items.value.size)
        assertEquals(state.currentTrack?.id, queueDao.items.value.first().trackId)
        assertEquals(state.currentIndex, queueDao.state.value?.currentIndex)
    }

    @Test
    fun `queue rewrite during removal preserves favorite state`() {
        val favorites = testFavoritesRepository(trackDao)
        testScope.launch { favorites.toggleFavorite(localB, true) }
        advanceUntilIdle()

        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 0)
        advanceUntilIdle()
        coordinator.removeQueueItem(0)
        advanceUntilIdle()

        val rowB = trackDao.rows[localB.providerId to localB.id]!!
        assertTrue(rowB.isFavorite)
    }

    @Test
    fun `removal on a restored queue persists the renumbered queue`() {
        seedQueue(localA, localB, currentIndex = 1)
        coordinator = newCoordinator()
        advanceUntilIdle()

        coordinator.removeQueueItem(0)
        advanceUntilIdle()

        assertEquals(listOf(localB), coordinator.state.value.queue)
        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(1, queueDao.items.value.size)
        assertEquals(0, queueDao.state.value?.currentIndex)
        // Removal of a non-current item must not have started playback.
        assertEquals(0, controller.loadCount)
        assertEquals(0, controller.playCalls)
    }
}
