package com.naudio.app.ui

import com.naudio.core.database.dao.TrackDao
import com.naudio.core.database.entity.TrackEntity
import com.naudio.core.model.Track
import com.naudio.data.repository.FavoritesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Deterministic LibraryViewModel tests: state mapping and favorite removal. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dao: FakeTrackDao
    private lateinit var viewModel: LibraryViewModel

    private val itunesTrack = Track("1440913503", "itunes", "Around the World", "Daft Punk", durationMs = 224_000L)
    private val ytmTrack = Track("y1", "ytmusic", "YTM Only", "Artist Y")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dao = FakeTrackDao()
        viewModel = LibraryViewModel(FavoritesRepository(dao))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** uiState uses WhileSubscribed; tests subscribe it on the test scope. */
    private fun collectInBackground(flow: StateFlow<*>, scope: CoroutineScope) {
        scope.launch { flow.collect {} }
    }

    @Test
    fun `initial state is empty`() = runTest {
        collectInBackground(viewModel.uiState, backgroundScope)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isEmpty)
        assertEquals(emptyList<Track>(), viewModel.uiState.value.favorites)
    }

    @Test
    fun `favorites emitted by the repository appear in the UI state`() = runTest {
        collectInBackground(viewModel.uiState, backgroundScope)
        dao.upsertTrack(
            TrackEntity(
                id = itunesTrack.id,
                providerId = itunesTrack.providerId,
                title = itunesTrack.title,
                artist = itunesTrack.artist,
                durationMs = 224_000L,
                isFavorite = true,
                savedAt = 1L,
            ),
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isEmpty)
        assertEquals(listOf(itunesTrack), viewModel.uiState.value.favorites)
    }

    @Test
    fun `onRemoveFavorite removes the track via the repository`() = runTest {
        collectInBackground(viewModel.uiState, backgroundScope)
        dao.upsertTrack(
            TrackEntity(
                id = itunesTrack.id,
                providerId = itunesTrack.providerId,
                title = itunesTrack.title,
                artist = itunesTrack.artist,
                durationMs = 224_000L,
                isFavorite = true,
                savedAt = 1L,
            ),
        )
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isEmpty)

        viewModel.onRemoveFavorite(itunesTrack)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
        assertEquals(false, dao.rows[itunesTrack.providerId to itunesTrack.id]?.isFavorite)
    }

    @Test
    fun `unplayable YTM tracks can be favorited and removed like any other`() = runTest {
        collectInBackground(viewModel.uiState, backgroundScope)
        dao.upsertTrack(
            TrackEntity(
                id = ytmTrack.id,
                providerId = ytmTrack.providerId,
                title = ytmTrack.title,
                artist = ytmTrack.artist,
                durationMs = 0L,
                isFavorite = true,
                savedAt = 2L,
            ),
        )
        advanceUntilIdle()
        assertEquals(listOf(ytmTrack), viewModel.uiState.value.favorites)

        viewModel.onRemoveFavorite(ytmTrack)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isEmpty)
    }

    private class FakeTrackDao : TrackDao {
        val rows = mutableMapOf<Pair<String, String>, TrackEntity>()
        private val backing = MutableStateFlow<List<TrackEntity>>(emptyList())

        /** Read-only view for tests that want the raw published list. */
        val published: List<TrackEntity> get() = backing.value

        private fun publish() {
            backing.value = rows.values
                .filter { it.isFavorite }
                .sortedByDescending { it.savedAt ?: 0L }
        }

        override fun observeFavorites() = backing

        override suspend fun upsertTrack(track: TrackEntity) {
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
}
