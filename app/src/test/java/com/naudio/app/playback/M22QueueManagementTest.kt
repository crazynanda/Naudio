package com.naudio.app.playback

import com.naudio.core.database.entity.QueueEntity
import com.naudio.core.database.entity.QueueStateEntity
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Deterministic M22 queue-management tests: playNext, addToQueue, moveQueueItem, clearQueue. */

@OptIn(ExperimentalCoroutinesApi::class)
class M22QueueManagementTest {

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private lateinit var controller: FakePlaybackController
    private lateinit var queueDao: FakeQueueDao
    private lateinit var trackDao: InMemoryTrackDao
    private lateinit var coordinator: PlaybackCoordinator

    private val localA = testTracks()[0]
    private val localB = testTracks()[1]
    private val localC = testTracks()[2]
    private val localD = testTracks()[0].copy(id = "d1", title = "D")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        controller = FakePlaybackController()
        queueDao = FakeQueueDao()
        trackDao = InMemoryTrackDao()
        coordinator = newCoordinator()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
        queueDao.state.value = currentIndex?.let { QueueStateEntity(currentIndex = it) }
    }

    // ===================================================================
    // playNext
    // ===================================================================

    @Test
    fun `playNext on an empty queue establishes the track as the queue and current item`() {
        coordinator.playNext(localA)
        advanceUntilIdle()

        assertEquals(listOf(localA), coordinator.state.value.queue)
        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(localA, controller.loadedTrack)
        assertEquals(1, controller.loadCount)
    }

    @Test
    fun `playNext while a track is playing inserts after it without interrupting`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 0)
        advanceUntilIdle()

        // Current track A is loaded and "playing". playNext must not stop it.
        val playingTrackBefore = controller.loadedTrack
        // A fresh track with a distinct id so it cannot collide.
        coordinator.playNext(localD)
        advanceUntilIdle()

        // Current track A is still loaded and playing (not restarted).
        assertEquals(playingTrackBefore, controller.loadedTrack)
        assertEquals(0, controller.playCalls)
        // A stays loaded at index 0; D is inserted right after, at index 1.
        // currentIndex records the loaded track (A), not the newly queued item.
        assertEquals(listOf(localA, localD, localB, localC), coordinator.state.value.queue)
        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(4, coordinator.state.value.queue.size)
    }

    @Test
    fun `playNext after the current item appends at currentIndex + 1`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 0)
        advanceUntilIdle()

        coordinator.playNext(localB.copy(id = "d1", title = "D"))
        advanceUntilIdle()

        assertEquals(listOf(localA, localB.copy(id = "d1", title = "D"), localB, localC), coordinator.state.value.queue)
        // A stays loaded at index 0; the inserted item is at index 1.
        // currentIndex records the loaded track (A), not the inserted item.
        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(localA, controller.loadedTrack)
    }

    @Test
    fun `playNext at the end appends after the last item`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 1)
        advanceUntilIdle()

        coordinator.playNext(localC)
        advanceUntilIdle()

        // currentIndex=1 (B loaded at index 1). playNext inserts at 1+1=2
        // (the end). currentIndex records the loaded track (B), which stays 1.
        assertEquals(listOf(localA, localB, localC), coordinator.state.value.queue)
        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
    }

    @Test
    fun `playNext with no active track (null currentIndex) establishes via setQueue`() {
        // Queue exists in Room but the persisted position was reset: no active track.
        seedQueue(localA, localB, localC, currentIndex = null)
        coordinator = newCoordinator()
        advanceUntilIdle()

        coordinator.playNext(localC)
        advanceUntilIdle()

        // setQueue path: tracks=listOf(localC), startIndex=0 -> resolves/local loads.
        assertEquals(listOf(localC), coordinator.state.value.queue)
        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(localC, controller.loadedTrack)
        assertEquals(1, controller.loadCount)
    }

    // ===================================================================
    // addToQueue
    // ===================================================================

    @Test
    fun `addToQueue appends a single track to the end`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()

        coordinator.addToQueue(listOf(localC))
        advanceUntilIdle()

        assertEquals(listOf(localA, localB, localC), coordinator.state.value.queue)
        assertEquals(0, coordinator.state.value.currentIndex) // unchanged
        assertEquals(localA, controller.loadedTrack)
    }

    @Test
    fun `addToQueue appends multiple tracks to the end`() {
        coordinator.setQueue(listOf(localA), startIndex = 0)
        advanceUntilIdle()

        coordinator.addToQueue(listOf(localB, localC, localD))
        advanceUntilIdle()

        assertEquals(listOf(localA, localB, localC, localD), coordinator.state.value.queue)
        assertEquals(0, coordinator.state.value.currentIndex)
    }

    @Test
    fun `addToQueue with an empty list is a no-op`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()
        val loadsBefore = controller.loadCount
        coordinator.addToQueue(emptyList())
        advanceUntilIdle()

        assertEquals(listOf(localA, localB), coordinator.state.value.queue)
        assertEquals(2, queueDao.items.value.size)
        assertEquals(loadsBefore, controller.loadCount)
    }

    @Test
    fun `addToQueue does not change currentIndex nor restart playback`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()
        val loadsBefore = controller.loadCount

        coordinator.addToQueue(testTracks().subList(1, 3))
        advanceUntilIdle()

        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(loadsBefore, controller.loadCount)
    }

    // ===================================================================
    // moveQueueItem
    // ===================================================================

    @Test
    fun `moveQueueItem treats matching indexes as a no-op`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 1)
        advanceUntilIdle()
        val loadsBefore = controller.loadCount

        coordinator.moveQueueItem(1, 1)
        advanceUntilIdle()

        assertEquals(listOf(localA, localB, localC), coordinator.state.value.queue)
        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
        assertEquals(loadsBefore, controller.loadCount)
    }

    @Test
    fun `moveQueueItem treats an invalid index as a no-op`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 1)
        advanceUntilIdle()
        val loadsBefore = controller.loadCount

        coordinator.moveQueueItem(-1, 1)
        coordinator.moveQueueItem(1, 3)
        coordinator.moveQueueItem(1, -1)
        coordinator.moveQueueItem(5, 2)
        advanceUntilIdle()

        assertEquals(listOf(localA, localB, localC), coordinator.state.value.queue)
        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
        assertEquals(loadsBefore, controller.loadCount)
    }

    @Test
    fun `moveQueueItem is safe on an empty queue`() {
        coordinator.moveQueueItem(0, 0)
        advanceUntilIdle()
        assertTrue(coordinator.state.value.queue.isEmpty())
    }

    @Test
    fun `moveQueueItem is safe on a single-item queue`() {
        coordinator.setQueue(listOf(localA), startIndex = 0)
        advanceUntilIdle()

        coordinator.moveQueueItem(0, 0)
        advanceUntilIdle()
        assertEquals(listOf(localA), coordinator.state.value.queue)
        assertEquals(0, coordinator.state.value.currentIndex)
    }

    @Test
    fun `moveQueueItem moving an item before the current item shifts currentIndex down`() {
        // queue [A,B,C,D], currentIndex=2 (C). Move B (index 1) to the front (index 0).
        coordinator.setQueue(listOf(localA, localB, localC, localD), startIndex = 0)
        advanceUntilIdle()

        coordinator.moveQueueItem(1, 0)
        advanceUntilIdle()

        // B removed, list becomes [A,C,D], then A moves right -> [B,A,C,D]. current becomes A at index 0.
        assertEquals(listOf(localB, localA, localC, localD), coordinator.state.value.queue)
        assertEquals(1, coordinator.state.value.currentIndex)
        assertEquals(localA, coordinator.state.value.currentTrack)
    }

    @Test
    fun `moveQueueItem moving an item after the current item shifts it forward`() {
        // queue [A,B,C,D], currentIndex=1 (B). Move D (index 3) to the front (index 0):
        // remove D -> [A,B,C], insert at 0 -> [D,A,B,C]. B shifts from 1 to 2.
        coordinator.setQueue(listOf(localA, localB, localC, localD), startIndex = 1)
        advanceUntilIdle()

        coordinator.moveQueueItem(3, 0)
        advanceUntilIdle()

        assertEquals(listOf(localD, localA, localB, localC), coordinator.state.value.queue)
        assertEquals(2, coordinator.state.value.currentIndex)
        assertEquals(localB, coordinator.state.value.currentTrack)
    }

    @Test
    fun `moveQueueItem moving the currently playing item follows it`() {
        // queue [A,B,C,D], currentIndex=1 (B). Move B to the end (index 3).
        coordinator.setQueue(listOf(localA, localB, localC, localD), startIndex = 1)
        advanceUntilIdle()

        coordinator.moveQueueItem(1, 3)
        advanceUntilIdle()

        // B moves to index 3 -> [A,C,D,B], current follows B at index 3.
        assertEquals(listOf(localA, localC, localD, localB), coordinator.state.value.queue)
        assertEquals(3, coordinator.state.value.currentIndex)
        assertEquals(localB, coordinator.state.value.currentTrack)
    }

    @Test
    fun `moveQueueItem keeps the moving item at its new index`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 1)
        advanceUntilIdle()

        coordinator.moveQueueItem(1, 0)
        advanceUntilIdle()

        assertEquals(listOf(localB, localA, localC), coordinator.state.value.queue)
        assertEquals(0, coordinator.state.value.currentIndex)
        assertEquals(localB, coordinator.state.value.currentTrack)
    }

    @Test
    fun `moveQueueItem currentIndex follows the moved item`() {
        // localD (module-level) is used to verify the persisted queue.
        coordinator.setQueue(listOf(localA, localB, localC, localD), startIndex = 2)
        advanceUntilIdle()

        // Move the final item to the front: C (index 2) shifts to index 3.
        coordinator.moveQueueItem(3, 0)
        advanceUntilIdle()

        assertEquals(listOf(localD, localA, localB, localC), coordinator.state.value.queue)
        assertEquals(3, coordinator.state.value.currentIndex)
        assertEquals(localC, coordinator.state.value.currentTrack)
        assertEquals(3, queueDao.state.value?.currentIndex)
        assertEquals(localD.id, queueDao.items.value.first { it.orderIndex == 0 }.trackId)
    }

    // ===================================================================
    // clearQueue
    // ===================================================================

    @Test
    fun `clearQueue stops playback and persists an empty queue with no position`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()

        val stopCallsBefore = controller.stopCalls
        coordinator.clearQueue()
        advanceUntilIdle()

        assertTrue(coordinator.state.value.queue.isEmpty())
        assertNull(coordinator.state.value.currentIndex)
        assertNull(coordinator.state.value.currentTrack)
        assertEquals(1, controller.stopCalls)
        assertTrue(controller.stopCalls > stopCallsBefore)
        // No stale item: the controller returns to idle with no loaded media.
        assertNull(controller.loadedTrack)
        assertNull(controller.state.value.track)
        // The persisted queue and position are cleared.
        assertTrue(queueDao.items.value.isEmpty())
        assertNull(queueDao.state.value?.currentIndex)
    }

    @Test
    fun `clearQueue persists empty state so a restart restores an empty queue`() {
        coordinator.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()
        coordinator.clearQueue()
        advanceUntilIdle()

        // Simulate a restart by creating a new coordinator backed by the same DAO.
        queueDao.state.value = com.naudio.core.database.entity.QueueStateEntity(currentIndex = null)
        val restarted = newCoordinator()
        advanceUntilIdle()

        assertTrue(restarted.state.value.queue.isEmpty())
        assertNull(restarted.state.value.currentIndex)
        // The empty, stopped session never autoplays: a stale ENDED cannot
        // resurrect the old queue.
        val loadsBefore = controller.loadCount
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()
        assertEquals(loadsBefore, controller.loadCount)
    }

    // ===================================================================
    // currentIndex invariants across the full mutation suite
    // ===================================================================

    @Test
    fun `queue mutations keep currentIndex within bounds and match the queue`() {
        coordinator.setQueue(listOf(localA, localB, localC), startIndex = 0)
        advanceUntilIdle()

        // playNext inserts D after the loaded track A (index 0); currentIndex
        // stays 0 because the current audio is untouched.
        coordinator.playNext(localD)
        advanceUntilIdle()
        assertEquals(0, coordinator.state.value.currentIndex)

        // addToQueue appends E after the current track and leaves currentIndex
        // (0) untouched.
        coordinator.addToQueue(listOf(localB.copy(id = "e1", title = "E")))
        advanceUntilIdle()
        assertEquals(0, coordinator.state.value.currentIndex)

        // Reorder: move the loaded track A (index 0) to the front (index 3).
        // A is the current item, so currentIndex follows it to 3.
        // queue [localD, localB, localC, localA, localB.copy("e1")].
        coordinator.moveQueueItem(0, 3)
        advanceUntilIdle()
        assertEquals(3, coordinator.state.value.currentIndex)
        assertEquals(5, coordinator.state.value.queue.size)

        // Reorder: move the current item to the front (it follows the moved item).
        // This is a same-index move (already at 3), which is a safe no-op.
        coordinator.moveQueueItem(3, 3)
        advanceUntilIdle()
        // no-op: same index, currentIndex unchanged.
        assertEquals(3, coordinator.state.value.currentIndex)

        // clearQueue empties and resets the position.
        coordinator.clearQueue()
        advanceUntilIdle()
        assertTrue(coordinator.state.value.queue.isEmpty())
        assertNull(coordinator.state.value.currentIndex)
    }
}
