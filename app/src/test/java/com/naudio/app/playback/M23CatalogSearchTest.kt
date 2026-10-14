package com.naudio.app.playback

import com.naudio.core.database.dao.HistoryDao
import com.naudio.core.database.entity.HistoryEntity
import com.naudio.core.model.CatalogAlbumSummary
import com.naudio.core.model.CatalogArtistSummary
import com.naudio.core.model.SearchCategory
import com.naudio.core.model.Track
import com.naudio.core.player.PlaybackStatus
import com.naudio.data.CatalogSearchCategorizer
import com.naudio.data.provider.ProviderRegistry
import com.naudio.data.repository.CatalogQueryState
import com.naudio.data.repository.CatalogSearchResult
import com.naudio.data.repository.HistoryRepository
import com.naudio.data.repository.LibraryRepository
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.Page
import com.naudio.provider.api.PageToken
import com.naudio.provider.api.ProviderId
import com.naudio.app.ui.HomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/** Deterministic M23 catalog-search tests: categorization, navigation ids,
 *  provider fallback, category state (via [HomeViewModel]), and track-options
 *  routing.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class M23CatalogSearchTest {

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private lateinit var controller: FakePlaybackController
    private lateinit var queueDao: FakeQueueDao
    private lateinit var trackDao: InMemoryTrackDao

    private val localTracks = listOf(
        Track(
            id = "t1",
            providerId = "local",
            title = "First Song",
            artist = "The Artists",
            album = "First Album",
            artistId = null,
            albumId = null,
        ),
        Track(
            id = "t2",
            providerId = "local",
            title = "Second Song",
            artist = "The Artists",
            album = "First Album",
            artistId = null,
            albumId = null,
        ),
        Track(
            id = "t3",
            providerId = "local",
            title = "Third Song",
            artist = "Another Band",
            album = "Second Album",
            artistId = null,
            albumId = null,
        ),
    )

    private val ytmTracks = listOf(
        Track(
            id = "t1",
            providerId = "ytmusic",
            title = "Wonderwall",
            artist = "Oasis",
            album = "What's the Story",
            artistId = "UCmMUZbaYdNH0bEd1PAlAqsA",
            albumId = "MPREb_9nqEki4ZDpp",
        ),
        Track(
            id = "t2",
            providerId = "ytmusic",
            title = "Live Forever",
            artist = "Oasis",
            album = "What's the Story",
            artistId = "UCmMUZbaYdNH0bEd1PAlAqsA",
            albumId = "MPREb_9nqEki4ZDpp",
        ),
        Track(
            id = "t3",
            providerId = "ytmusic",
            title = "Shallow",
            artist = "Lady Gaga",
            album = "A Star Is Born",
            artistId = "UCzWWrDMxQR1t7R20f2xiTcA",
            albumId = "MPREb_UnknownAlbum",
        ),
    )

    private fun mockMetadataProvider(
        tracks: List<Track> = emptyList(),
        providerId: String = "ytmusic",
    ): MetadataProvider = object : MetadataProvider {
        override val id: ProviderId = ProviderId(providerId)
        override val displayName: String = "Mock"

        override suspend fun searchTracks(
            query: String,
            token: PageToken?,
            limit: Int,
        ): Page<Track> = Page(items = tracks, nextToken = null)

        override suspend fun lookupTrack(id: String): Track? = null
        override suspend fun getArtist(artistId: String): com.naudio.core.model.ArtistDetail? = null
        override suspend fun getAlbum(albumId: String): com.naudio.core.model.AlbumDetail? = null
    }

    private fun noIdMetadataProvider(
        tracks: List<Track> = emptyList(),
        providerId: String = "local",
    ): MetadataProvider = object : MetadataProvider {
        override val id: ProviderId = ProviderId(providerId)
        override val displayName: String = "NoId"

        override suspend fun searchTracks(
            query: String,
            token: PageToken?,
            limit: Int,
        ): Page<Track> = Page(items = tracks, nextToken = null)

        override suspend fun lookupTrack(id: String): Track? = null
        override suspend fun getArtist(artistId: String): com.naudio.core.model.ArtistDetail? = null
        override suspend fun getAlbum(albumId: String): com.naudio.core.model.AlbumDetail? = null
    }

    // =========================================================================
    // Categorization (M23): [CatalogSearchCategorizer] -> [CatalogSearchProjection]
    // =========================================================================

    @Test
    fun `songs category preserves song order and all tracks`() = runTest {
        val projection = CatalogSearchCategorizer.categorize(
            ytmTracks,
            SearchCategory.SONGS,
        )

        assertEquals(ytmTracks, projection.songs)
        assertTrue(projection.artists.isEmpty())
        assertTrue(projection.albums.isEmpty())
    }

    @Test
    fun `artists category deduplicates by providerId + artistId and keeps first`() = runTest {
        val projection = CatalogSearchCategorizer.categorize(ytmTracks, SearchCategory.ARTISTS)

        assertEquals(2, projection.artists.size)
        val names = projection.artists.map { it.name }.toSet()
        assertTrue(names.contains("Oasis"))
        assertTrue(names.contains("Lady Gaga"))
    }

    @Test
    fun `artists category drops tracks without a provider-reported artistId`() = runTest {
        val projection = CatalogSearchCategorizer.categorize(localTracks, SearchCategory.ARTISTS)

        assertTrue(projection.artists.isEmpty())
    }

    @Test
    fun `albums category deduplicates and preserves display metadata`() = runTest {
        val projection = CatalogSearchCategorizer.categorize(ytmTracks, SearchCategory.ALBUMS)

        assertEquals(2, projection.albums.size)
        val titles = projection.albums.map { it.title }
        assertTrue(titles.contains("What's the Story"))
        assertTrue(titles.contains("A Star Is Born"))
    }

    @Test
    fun `all category returns songs plus artists plus albums`() = runTest {
        val projection = CatalogSearchCategorizer.categorize(ytmTracks, SearchCategory.ALL)

        assertEquals(ytmTracks, projection.songs)
        assertEquals(2, projection.artists.size)
        assertEquals(2, projection.albums.size)
    }

    @Test
    fun `artists are never duplicated across the same provider+id`() = runTest {
        val tracks = listOf(
            ytmTracks[0].copy(title = "A"),
            ytmTracks[1].copy(title = "B"),
            Track(
                id = "x",
                providerId = "ytmusic",
                title = "C",
                artist = "Oasis",
                album = null,
                artistId = "UCmMUZbaYdNH0bEd1PAlAqsA",
                albumId = null,
            ),
        )
        val projection = CatalogSearchCategorizer.categorize(tracks, SearchCategory.ARTISTS)

        assertEquals(1, projection.artists.size)
        assertEquals("Oasis", projection.artists[0].name)
    }

    // =========================================================================
    // Navigation (provider+id, no fabricated ids)
    // =========================================================================

    @Test
    fun `artist result carries the provider-reported catalog id`() = runTest {
        val projection = CatalogSearchCategorizer.categorize(ytmTracks, SearchCategory.ARTISTS)
        val artists = projection.artists

        val oasis = artists.first { it.name == "Oasis" }
        assertEquals("UCmMUZbaYdNH0bEd1PAlAqsA", oasis.id)
        assertEquals("ytmusic", oasis.providerId)
        assertEquals("Oasis", oasis.name)
    }

    @Test
    fun `album result carries the provider-reported catalog id`() = runTest {
        val projection = CatalogSearchCategorizer.categorize(ytmTracks, SearchCategory.ALBUMS)
        val albums = projection.albums

        val studio = albums.first { it.title == "What's the Story" }
        assertEquals("MPREb_9nqEki4ZDpp", studio.id)
        assertEquals("ytmusic", studio.providerId)
        assertEquals("Oasis", studio.artist)
    }

    @Test
    fun `no fabricated ids from display names`() = runTest {
        val fakeTracks = listOf(
            Track(
                id = "t1",
                providerId = "ytmusic",
                title = "Fake",
                artist = "Evil Genius",
                album = "Evil Album",
                artistId = null,
                albumId = null,
            ),
        )
        val projection = CatalogSearchCategorizer.categorize(fakeTracks, SearchCategory.ARTISTS)

        assertTrue(projection.artists.isEmpty())
    }

    // =========================================================================
    // Repository: reactive searchCatalog (debounce, category, provider routing)
    // =========================================================================

    @Test
    fun `local provider yields song results but no artist or album ids`() = runTest {
        val provider = mockMetadataProvider(tracks = localTracks)
        val repo = LibraryRepository(mockRegistryWith(provider))

        val songsState = repo.search(flowOf("anything")).first()
        assertTrue(songsState is com.naudio.data.repository.LibraryQueryState.Results)
        val songs = (songsState as com.naudio.data.repository.LibraryQueryState.Results).tracks
        assertEquals(localTracks, songs)

        val catalog = repo.searchCatalog(flowOf("anything"), SearchCategory.ARTISTS).first()
        assertTrue("BAD CATALOG STATE", catalog is CatalogQueryState.Results)
        val results = (catalog as CatalogQueryState.Results).results
        advanceUntilIdle()
        assertTrue(results[1] is CatalogSearchResult.Artists)
        assertEquals(0, (results[1] as CatalogSearchResult.Artists).items.size)
    }

    @Test
    fun `song-only provider search never crashes on missing ids`() = runTest {
        val repo = LibraryRepository(mockRegistry())

        val categories = listOf(
            SearchCategory.ALL,
            SearchCategory.SONGS,
            SearchCategory.ARTISTS,
            SearchCategory.ALBUMS,
        )
        for (category in categories) {
            val state = repo.searchCatalog(flowOf("anything"), category).first()
            assertTrue(state is CatalogQueryState.Results)
            val results = (state as CatalogQueryState.Results).results
            assertNotNull(results)
        }
    }

    @Test
    fun `artist cards are navigation-ready only when an id is present`() = runTest {
        val ytmProvider = mockMetadataProvider(tracks = ytmTracks)
        val localProvider = noIdMetadataProvider(tracks = localTracks)
        val ytmRegistry = mockRegistryWith(ytmProvider, localProvider)
        val ytmRepo = LibraryRepository(ytmRegistry)

        val ytmState = ytmRepo.searchCatalog(flowOf("daft"), SearchCategory.ARTISTS).first()
        assertTrue(ytmState is CatalogQueryState.Results)
        val ytmArtists = (ytmState as CatalogQueryState.Results).results[1] as CatalogSearchResult.Artists
        assertEquals(2, ytmArtists.items.size)

        val localRegistry = mockRegistryWith(localProvider)
        val localRepo = LibraryRepository(localRegistry)
        val localState = localRepo.searchCatalog(flowOf("anything"), SearchCategory.ARTISTS).first()
        assertTrue(localState is CatalogQueryState.Results)
        val localArtists = (localState as CatalogQueryState.Results).results[1] as CatalogSearchResult.Artists
        assertEquals(0, localArtists.items.size)
    }

    // =========================================================================
    // ViewModel: category selection keeps state sane (M23)
    // =========================================================================

    private fun TestScope.createHomeViewModel(provider: MetadataProvider): Pair<HomeViewModel, Job> {
        val repository = LibraryRepository(mockRegistryWith(provider))
        val viewModel = HomeViewModel(
            repository = repository,
            registry = mockRegistryWith(provider),
            historyRepository = historyRepositoryWith(emptyList()),
        )
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        return viewModel to collectJob
    }

    @Test
    fun `category selection drives the same underlying song query`() = runTest {
        val provider = mockMetadataProvider(tracks = ytmTracks)
        val (viewModel, collectJob) = createHomeViewModel(provider)

        viewModel.onQueryChange("daft")
        viewModel.setCategory(SearchCategory.ARTISTS)
        advanceTimeBy(300)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CatalogQueryState.Results::class, state.searchState::class)
        assertEquals(SearchCategory.ARTISTS, (state.searchState as CatalogQueryState.Results).category)
        assertEquals(3, (state.searchState as CatalogQueryState.Results).results.size)
        assertEquals(2, ((state.searchState as CatalogQueryState.Results).results[1] as CatalogSearchResult.Artists).items.size)
        collectJob.cancel()
    }

    @Test
    fun `viewModel categorizes after query plus category`() = runTest {
        val provider = mockMetadataProvider(tracks = ytmTracks)
        val (viewModel, collectJob) = createHomeViewModel(provider)

        viewModel.onQueryChange("daft")
        viewModel.setCategory(SearchCategory.SONGS)
        advanceTimeBy(300)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CatalogQueryState.Results::class, state.searchState::class)
        assertEquals(SearchCategory.SONGS, (state.searchState as CatalogQueryState.Results).category)
        assertEquals(3, (state.searchState as CatalogQueryState.Results).results.size)
        assertEquals(ytmTracks.size, ((state.searchState as CatalogQueryState.Results).results[0] as CatalogSearchResult.Tracks).tracks.size)
        collectJob.cancel()
    }

    // =========================================================================
    // Track options routing (M22 behaviour unchanged)
    // =========================================================================

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        controller = FakePlaybackController()
        queueDao = FakeQueueDao()
        trackDao = InMemoryTrackDao()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun advanceUntilIdle() = testScope.advanceUntilIdle()

    @Test
    fun `playNext preserves currentIndex and does not restart current audio`() = runTest {
        val coordinator = PlaybackCoordinator(
            playbackController = controller,
            libraryRepository = testLibraryRepository(),
            queueRepository = testQueueRepository(trackDao, queueDao),
            scope = testScope,
        )
        coordinator.setQueue(listOf(localTracks[0], localTracks[1]), startIndex = 0)
        advanceUntilIdle()
        controller.emitStatus(PlaybackStatus.READY)

        val before = coordinator.state.value
        assertEquals(0, before.currentIndex)
        assertEquals(localTracks[0], controller.loadedTrack)

        coordinator.playNext(localTracks[2])
        advanceUntilIdle()

        val after = coordinator.state.value
        assertEquals(listOf(localTracks[0], localTracks[2], localTracks[1]), after.queue)
        assertEquals(0, after.currentIndex)
        assertEquals(localTracks[0], controller.loadedTrack)
        assertEquals(0, controller.playCalls)
        assertEquals(0, controller.stopCalls)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** A registry whose active provider is the first listed provider. */
    private fun mockRegistry(vararg providers: MetadataProvider): ProviderRegistry {
        return ProviderRegistry(newProviderList(providers), emptyList())
    }

    /** A registry whose active provider is the first listed provider. */
    private fun mockRegistryWith(vararg providers: MetadataProvider): ProviderRegistry {
        return ProviderRegistry(newProviderList(providers), emptyList())
    }

    /** A library repository backed by a registry whose active provider is the
     *  first listed provider. Defaults to a song-only provider.
     */
    private fun mockLibraryRepository(vararg providers: MetadataProvider): LibraryRepository {
        return LibraryRepository(ProviderRegistry(newProviderList(providers), emptyList()))
    }

    /** The registry's constructor takes a List<MetadataProvider>; a plain
     *  vararg is used directly, so the default (no providers) would be an empty
     *  list that never becomes active. Prepend the song-only provider so every
     *  registry still has an active metadata provider.
     */
    private fun newProviderList(providers: Array<out MetadataProvider>): List<MetadataProvider> {
        if (providers.isNotEmpty()) return listOf<MetadataProvider>(providers[0], *providers.copyOfRange(1, providers.size))
        return listOf<MetadataProvider>(noIdMetadataProvider())
    }

    /** Minimal test double of [HistoryRepository] with an in-memory DAO. */
    private fun historyRepositoryWith(events: List<HistoryEntity>): HistoryRepository {
        val dao = FakeHistoryDao(events)
        return HistoryRepository(
            historyDao = dao,
            scope = CoroutineScope(Dispatchers.Main.immediate + Job()),
        )
    }

    /** In-memory [HistoryDao] with append-only and newest-first ordering. */
    private class FakeHistoryDao(
        initial: List<HistoryEntity> = emptyList(),
    ) : HistoryDao {
        private val rows = mutableListOf<HistoryEntity>()
        private val state = MutableStateFlow<List<HistoryEntity>>(emptyList())
        private var nextId = 1L

        init {
            rows += initial
            state.value = newestFirst()
        }

        private fun newestFirst(): List<HistoryEntity> = rows.sortedWith(
            kotlin.comparisons.compareByDescending<HistoryEntity> { it.playedAt }.thenByDescending { it.id },
        )

        override fun observeRecent(limit: Int): Flow<List<HistoryEntity>> =
            state.map { entries -> entries.take(limit) }

        override suspend fun insert(entry: HistoryEntity): Long {
            val id = nextId++
            rows += entry.copy(id = id)
            state.value = newestFirst()
            return id
        }

        override suspend fun deleteBeyondNewest(keep: Int) {
            val survivors = newestFirst().take(keep)
            rows.clear()
            rows += survivors
            state.value = newestFirst()
        }

        override suspend fun count(): Int = rows.size
    }
}
