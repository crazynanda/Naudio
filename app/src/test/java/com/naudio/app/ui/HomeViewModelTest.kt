package com.naudio.app.ui

import com.naudio.core.database.dao.HistoryDao
import com.naudio.core.database.entity.HistoryEntity
import com.naudio.core.model.History
import com.naudio.core.model.Track
import com.naudio.data.provider.ProviderRegistry
import com.naudio.data.repository.HistoryRepository
import com.naudio.data.repository.LibraryRepository
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.Page
import com.naudio.provider.api.PageToken
import com.naudio.provider.api.ProviderId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M17 Home coverage. The "Recently Played" row is driven entirely by
 * `HomeUiState.recentlyPlayed`, so the two properties the UI depends on are
 * asserted here: the list is the repository's own newest-first order, and it is
 * empty when nothing has been played (which is what makes the screen hide the
 * section instead of showing an empty carousel).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private var mainScope: CoroutineScope? = null

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainScope?.cancel()
        mainScope = null
    }

    /**
     * Everything is wired onto the test's own scheduler, so a single
     * `advanceUntilIdle()` genuinely runs the ViewModel pipeline.
     */
    private fun TestScope.viewModel(historyDao: HistoryDao): Pair<HomeViewModel, CoroutineScope> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        mainScope = scope
        val registry = ProviderRegistry(listOf(SearchlessProvider()), emptyList())
        val viewModel = HomeViewModel(
            repository = LibraryRepository(registry),
            registry = registry,
            historyRepository = HistoryRepository(historyDao, scope),
        )
        // uiState is shared WhileSubscribed, so a collector is what starts the
        // pipeline — exactly as the screen does.
        scope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        return viewModel to scope
    }

    @Test
    fun `home without history exposes an empty list so the section stays hidden`() = runTest {
        val (viewModel, _) = viewModel(FakeHistoryDao())

        assertTrue(viewModel.uiState.value.recentlyPlayed.isEmpty())
    }

    @Test
    fun `home with history exposes the newest events first`() = runTest {
        val dao = FakeHistoryDao()
        val (viewModel, _) = viewModel(dao)
        dao.add(entry(trackId = "old", playedAt = 100L))
        dao.add(entry(trackId = "new", playedAt = 200L))
        advanceUntilIdle()

        val recent = viewModel.uiState.value.recentlyPlayed
        assertEquals(listOf("new", "old"), recent.map { it.trackId })
    }

    @Test
    fun `a replayed track stays as two separate events`() = runTest {
        val dao = FakeHistoryDao()
        val (viewModel, _) = viewModel(dao)
        dao.add(entry(trackId = "shared", playedAt = 100L))
        dao.add(entry(trackId = "shared", playedAt = 200L))
        advanceUntilIdle()

        val recent = viewModel.uiState.value.recentlyPlayed
        // History is an event log, not a unique-track table: the UI must not
        // deduplicate it.
        assertEquals(2, recent.size)
    }

    @Test
    fun `the search pipeline still drives the state alongside history`() = runTest {
        val (viewModel, _) = viewModel(FakeHistoryDao())
        viewModel.onQueryChange("daft punk")
        advanceUntilIdle()

        assertEquals("daft punk", viewModel.uiState.value.query)
    }

    @Test
    fun `a history entry converts to the track the playback path expects`() {
        val track = History(
            id = 2L,
            providerId = "itunes",
            trackId = "1440847780",
            title = "Around the World",
            artist = "Daft Punk",
            album = "Discovery",
            artworkUrl = "https://example.test/art.jpg",
            durationMs = 429_000L,
            playedAt = 200L,
        ).toTrack()

        assertEquals("1440847780", track.id)
        assertEquals("itunes", track.providerId)
        assertEquals("Around the World", track.title)
        assertEquals("Daft Punk", track.artist)
        assertEquals("Discovery", track.album)
        assertEquals(429_000L, track.durationMs)
    }

    private fun entry(trackId: String, playedAt: Long) = HistoryEntity(
        providerId = "local",
        trackId = trackId,
        title = "Title $trackId",
        artist = "Artist",
        album = null,
        artworkUrl = null,
        durationMs = 1_000L,
        playedAt = playedAt,
    )

    /** Provider that returns nothing, so search state is irrelevant here. */
    private class SearchlessProvider : MetadataProvider {
        override val id = ProviderId("local")
        override val displayName = "Local"
        override suspend fun searchTracks(query: String, token: PageToken?, limit: Int) =
            Page(emptyList<Track>(), nextToken = null)

        override suspend fun lookupTrack(id: String): Track? = null
    }
}

/** In-memory HistoryDao with the repository's ordering semantics. */
private class FakeHistoryDao : HistoryDao {

    private val rows = mutableListOf<HistoryEntity>()
    private val state = MutableStateFlow<List<HistoryEntity>>(emptyList())

    fun add(entry: HistoryEntity) {
        rows += entry.copy(id = rows.size + 1L)
        state.value = rows.sortedWith(
            compareByDescending<HistoryEntity> { it.playedAt }.thenByDescending { it.id },
        )
    }

    override fun observeRecent(limit: Int): Flow<List<HistoryEntity>> =
        state.map { entries -> entries.take(limit) }

    override suspend fun insert(entry: HistoryEntity): Long {
        add(entry)
        return entry.id
    }

    override suspend fun deleteBeyondNewest(keep: Int) {
        val survivors = state.value.take(keep)
        rows.clear()
        rows += survivors
    }

    override suspend fun count(): Int = rows.size
}
