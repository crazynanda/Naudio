package com.naudio.app.ui

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.core.player.PlaybackController
import com.naudio.core.player.PlayerState
import com.naudio.core.player.PlaybackStatus
import com.naudio.data.repository.FavoritesRepository
import com.naudio.data.repository.LibraryRepository
import com.naudio.data.repository.TrackKey
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.Page
import com.naudio.provider.api.PageToken
import com.naudio.provider.api.PlaybackProvider
import com.naudio.provider.api.ProviderId
import com.naudio.data.provider.ProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic tests for the runtime queue: setQueue/skip/replace semantics,
 * ENDED auto-advance, and unplayable-track skipping — no Media3, no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var controller: FakePlaybackController
    private lateinit var repository: LibraryRepository
    private lateinit var favorites: FavoritesRepository
    private lateinit var viewModel: PlaybackViewModel

    private val localA = Track("a1", "local", "Track A", "Artist A")
    private val localB = Track("b1", "local", "Track B", "Artist B")
    private val localC = Track("c1", "local", "Track C", "Artist C")
    private val ytmB = Track("y1", "ytmusic", "YTM B", "Artist Y")

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        controller = FakePlaybackController()
        val itunesPlayback = RecordingPlayback(ProviderId("itunes"))
        repository = LibraryRepository(
            ProviderRegistry(
                providers = listOf(
                    FakeMetadata(ProviderId("local")),
                    FakeMetadata(ProviderId("itunes")),
                    FakeMetadata(ProviderId("ytmusic")),
                ),
                playbackProviders = listOf(
                    LocalPlayback(),
                    itunesPlayback,
                ),
            ),
        )
        favorites = FavoritesRepository(InMemoryTrackDao())
        viewModel = PlaybackViewModel(controller, repository, favorites)
        advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // setQueue semantics
    // ------------------------------------------------------------------

    @Test
    fun `setQueue loads the track at the start index and plays it`() = runTest {
        viewModel.setQueue(listOf(localA, localB, localC), startIndex = 1)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(localB, viewModel.uiState.value.currentTrack)
        assertEquals(3, viewModel.uiState.value.queue.size)
        assertEquals(localB, controller.loadedTrack)
    }

    @Test
    fun `setQueue with an out-of-range start index is ignored`() = runTest {
        viewModel.setQueue(listOf(localA, localB), startIndex = 5)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.currentIndex)
        assertNull(controller.loadedTrack)
    }

    @Test
    fun `setQueue with a negative start index is ignored`() = runTest {
        viewModel.setQueue(listOf(localA, localB), startIndex = -1)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.currentIndex)
        assertNull(controller.loadedTrack)
    }

    @Test
    fun `setQueue with an empty list is ignored`() = runTest {
        viewModel.setQueue(emptyList(), startIndex = 0)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.queue.isEmpty())
        assertNull(controller.loadedTrack)
    }

    @Test
    fun `queue replacement discards the old queue`() = runTest {
        viewModel.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()
        viewModel.setQueue(listOf(localC), startIndex = 0)
        advanceUntilIdle()

        assertEquals(listOf(localC), viewModel.uiState.value.queue)
        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals(localC, controller.loadedTrack)
    }

    @Test
    fun `onTrackSelected plays a single-item queue`() = runTest {
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
    fun `skipToNext advances and resolves the next track`() = runTest {
        viewModel.setQueue(listOf(localA, localB, localC), startIndex = 0)
        advanceUntilIdle()
        viewModel.skipToNext()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
    }

    @Test
    fun `skipToPrevious goes back and resolves the previous track`() = runTest {
        viewModel.setQueue(listOf(localA, localB, localC), startIndex = 2)
        advanceUntilIdle()
        viewModel.skipToPrevious()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
    }

    @Test
    fun `skipToNext at the final item is a safe no-op`() = runTest {
        viewModel.setQueue(listOf(localA, localB), startIndex = 1)
        advanceUntilIdle()
        viewModel.skipToNext()
        advanceUntilIdle()

        // Stays on the final item; no crash, no reload.
        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
        assertEquals(1, controller.loadCount)
    }

    @Test
    fun `skipToPrevious at the first item resumes the current track`() = runTest {
        viewModel.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()
        viewModel.skipToPrevious()
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals(1, controller.playCalls)
        assertEquals(localA, controller.loadedTrack)
    }

    @Test
    fun `skip without a queue is a safe no-op`() = runTest {
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
    fun `ENDED auto-advances to the next queue item`() = runTest {
        viewModel.setQueue(listOf(localA, localB), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
    }

    @Test
    fun `ENDED on the final item does not loop and keeps the final position`() = runTest {
        viewModel.setQueue(listOf(localA, localB), startIndex = 1)
        advanceUntilIdle()
        val loadsBefore = controller.loadCount
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        assertEquals(loadsBefore, controller.loadCount)
        // Position stays on the final item so it remains visible/favoritable.
        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(localB, viewModel.uiState.value.currentTrack)
    }

    @Test
    fun `distinct ENDED emissions do not double-advance`() = runTest {
        viewModel.setQueue(listOf(localA, localB, localC), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.ENDED)
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        // One advance: A -> B. The second ENDED (same distinct value) is ignored.
        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(localB, controller.loadedTrack)
    }

    // ------------------------------------------------------------------
    // unplayable tracks
    // ------------------------------------------------------------------

    @Test
    fun `user selection of an unplayable YTM track surfaces unavailable and does not load`() = runTest {
        viewModel.onTrackSelected(ytmB)
        advanceUntilIdle()

        assertEquals(PlaybackError.UNAVAILABLE, viewModel.playbackError.value)
        assertNull(controller.loadedTrack)
    }

    @Test
    fun `auto-advance skips an unplayable YTM item and reaches the next playable one`() = runTest {
        viewModel.setQueue(listOf(localA, ytmB, localC), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.ENDED) // A ended -> try B (YTM)
        advanceUntilIdle()

        // B was skipped; C is playing.
        assertEquals(2, viewModel.uiState.value.currentIndex)
        assertEquals(localC, controller.loadedTrack)
        // Exactly one surfaced error despite the skip.
        assertEquals(PlaybackError.UNAVAILABLE, viewModel.playbackError.value)
    }

    @Test
    fun `skipToNext over an unplayable item reaches the next playable one`() = runTest {
        viewModel.setQueue(listOf(localA, ytmB, localC), startIndex = 0)
        advanceUntilIdle()
        viewModel.skipToNext()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.currentIndex)
        assertEquals(localC, controller.loadedTrack)
    }

    @Test
    fun `queue of only unplayable items stops gracefully without loading`() = runTest {
        viewModel.setQueue(listOf(ytmB), startIndex = 0)
        advanceUntilIdle()

        assertEquals(PlaybackError.UNAVAILABLE, viewModel.playbackError.value)
        assertNull(controller.loadedTrack)
        // The index stays on the unplayable item so the UI can still show and
        // favorite it; no load occurs and no auto-advance loops.
        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals(ytmB, viewModel.uiState.value.currentTrack)
    }

    @Test
    fun `all remaining items unplayable stops the queue safely`() = runTest {
        viewModel.setQueue(listOf(localA, ytmB), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.ENDED)
        advanceUntilIdle()

        // A played; B (YTM) could not resolve. No further load happens, the
        // failure is surfaced exactly once, and the queue does not loop.
        assertEquals(1, controller.loadCount)
        assertEquals(localA, controller.loadedTrack)
        assertEquals(PlaybackError.UNAVAILABLE, viewModel.playbackError.value)
    }

    @Test
    fun `skipError surfaces only once for consecutive unplayable items`() = runTest {
        val ytmC = Track("y2", "ytmusic", "YTM C", "Artist Y")
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
    fun `favorite state of the current track is observed`() = runTest {
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
    fun `favoriting works for an unplayable YTM track`() = runTest {
        viewModel.onTrackSelected(ytmB) // unplayable -> error, but current index set
        advanceUntilIdle()

        viewModel.onToggleFavorite()
        advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.favoriteIds.contains(TrackKey(ytmB)),
        )
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class FakeMetadata(override val id: ProviderId) : MetadataProvider {
        override val displayName: String = id.value
        override suspend fun searchTracks(query: String, token: PageToken?, limit: Int): Page<Track> =
            Page(emptyList(), nextToken = null)

        override suspend fun lookupTrack(id: String): Track? = null
    }

    /** Resolves every local track to a fake local source. */
    private class LocalPlayback : PlaybackProvider {
        override val id: ProviderId = ProviderId("local")
        override suspend fun resolve(track: Track): AudioSource? =
            if (track.providerId == "local") AudioSource.Local("content://fake/${track.id}") else null
    }

    private class RecordingPlayback(override val id: ProviderId) : PlaybackProvider {
        override suspend fun resolve(track: Track): AudioSource? = null
    }

    /** Minimal REPLACE-faithful TrackDao fake for favorite-state tests. */
    private class InMemoryTrackDao : com.naudio.core.database.dao.TrackDao {
        val rows = mutableMapOf<Pair<String, String>, com.naudio.core.database.entity.TrackEntity>()
        private val state = MutableStateFlow<List<com.naudio.core.database.entity.TrackEntity>>(emptyList())

        private fun publish() {
            state.value = rows.values
                .filter { it.isFavorite }
                .sortedByDescending { it.savedAt ?: 0L }
        }

        override fun observeFavorites() = state

        override suspend fun upsertTrack(track: com.naudio.core.database.entity.TrackEntity) {
            rows[track.providerId to track.id] = track
            publish()
        }

        override suspend fun updateFavorite(
            providerId: String,
            trackId: String,
            isFavorite: Boolean,
            savedAt: Long?,
        ) {
            val key = providerId to trackId
            val existing = rows[key] ?: return
            rows[key] = existing.copy(isFavorite = isFavorite, savedAt = savedAt)
            publish()
        }
    }

    private class FakePlaybackController : PlaybackController {
        private val _state = MutableStateFlow(PlayerState())
        override val state: kotlinx.coroutines.flow.StateFlow<PlayerState> = _state

        var loadedTrack: Track? = null
        var loadCount = 0
        var playCalls = 0

        fun emitStatus(status: PlaybackStatus) {
            _state.value = PlayerState(status = status, track = loadedTrack)
        }

        override fun load(track: Track, source: AudioSource) {
            loadCount++
            loadedTrack = track
            _state.value = PlayerState(status = PlaybackStatus.READY, isPlaying = true, track = track)
        }

        override fun play() {
            playCalls++
        }

        override fun pause() {}
        override fun stop() {}
        override fun seekTo(positionMs: Long) {}
        override fun release() {}
    }
}
