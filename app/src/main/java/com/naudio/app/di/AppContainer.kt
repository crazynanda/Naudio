package com.naudio.app.di

import android.content.Context
import com.naudio.app.auto.AutoBrowseTree
import com.naudio.app.auto.AutoPlaybackGateway
import com.naudio.app.history.HistoryTracker
import com.naudio.app.playback.PlaybackCoordinator
import com.naudio.core.database.NaudioDatabase
import com.naudio.core.network.NaudioHttpClient
import com.naudio.core.player.AutoBrowseTreeProvider
import com.naudio.core.player.AutoPlaybackBridge
import com.naudio.core.player.MediaControllerPlaybackController
import com.naudio.core.player.PlaybackController
import com.naudio.data.provider.ProviderRegistry
import com.naudio.data.repository.FavoritesRepository
import com.naudio.data.repository.HistoryRepository
import com.naudio.data.repository.LibraryRepository
import com.naudio.data.repository.PlaylistRepository
import com.naudio.data.repository.QueueRepository
import com.naudio.data.repository.TransactionRunner
import com.naudio.provider.api.ProviderId
import com.naudio.provider.default.DefaultMetadataProvider
import com.naudio.provider.default.DefaultPlaybackProvider
import com.naudio.provider.itunes.ItunesMetadataProvider
import com.naudio.provider.itunes.ItunesPlaybackProvider
import com.naudio.provider.local.LocalMetadataProvider
import com.naudio.provider.local.LocalPlaybackProvider
import com.naudio.provider.ytmusic.YtMusicMetadataProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual, provider-based DI. Deliberately not Hilt at this milestone:
 * the graph is small and explicit construction documents the architecture.
 * When the graph grows (database, online providers), revisit.
 */
class AppContainer(context: Context) {

    private val applicationContext = context.applicationContext

    // Application-lifetime, shared Ktor HTTP client instance — created once,
    // reused for every request (never constructed per request). Declared
    // before the providers that receive it via constructor injection.
    val networkClient: HttpClient by lazy {
        NaudioHttpClient.create()
    }

    // Providers: registration order = default priority. Online providers
    // augment the catalog. Playback capabilities are registered separately and
    // route by Track.providerId — never by the active metadata provider.
    private val metadataProviders = listOf(
        DefaultMetadataProvider(),
        ItunesMetadataProvider(networkClient),
        LocalMetadataProvider(applicationContext),
        // Catalog metadata only: no YTM playback provider exists (and none may
        // be added), so selecting a YTM result safely resolves no source.
        YtMusicMetadataProvider(networkClient),
    )

    private val playbackProviders = listOf(
        DefaultPlaybackProvider(),
        LocalPlaybackProvider(),
        // Resolves the iTunes-served 30 s preview stream for iTunes tracks.
        ItunesPlaybackProvider(networkClient),
    )

    val providerRegistry = ProviderRegistry(metadataProviders, playbackProviders).apply {
        // Milestone 8 app configuration: iTunes stays the initially active
        // catalog source; the user can switch to "Local Device" from the Home
        // selector, which re-drives search through the same registry. Playback
        // routing is independent of the active catalog: it follows each
        // track's own providerId through the playback providers above.
        check(activate(ProviderId(ItunesMetadataProvider.PROVIDER_ID))) { "iTunes provider must be registered" }
    }

    val libraryRepository = LibraryRepository(providerRegistry)

    // The database is a single app-lifetime instance — initialized once, never
    // from a Composable or ViewModel.
    private val database: NaudioDatabase by lazy {
        NaudioDatabase.open(applicationContext)
    }

    val favoritesRepository: FavoritesRepository by lazy {
        FavoritesRepository(database.trackDao())
    }

    // Persistent playback queue storage (M10): shares the app-lifetime DB.
    val queueRepository: QueueRepository by lazy {
        QueueRepository(database.queueDao(), database.trackDao())
    }

    // User playlists (M13): shares the app-lifetime DB. Track persistence and
    // playlist membership writes run atomically through the shared
    // TransactionRunner.
    val playlistRepository: PlaylistRepository by lazy {
        PlaylistRepository(
            playlistDao = database.playlistDao(),
            trackDao = database.trackDao(),
            transactions = TransactionRunner.forDatabase(database),
        )
    }

    // M17: one application-lifetime scope. The retention trim it runs must
    // outlive whichever ViewModel or screen triggered the insert, and the
    // tracker must survive screen navigation — so both hang off the
    // application, never off a Composable.
    private val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    // App-lifetime playback controller; the service owns the real player.
    val playbackController: PlaybackController by lazy {
        MediaControllerPlaybackController(applicationContext)
    }

    // The ONE queue orchestrator (M14): app-lifetime and shared by the mobile
    // UI (PlaybackViewModel) and Android Auto (the media session gateway), so
    // queue movement, persistence and resolution have a single owner. The
    // scope is app-lifetime by design — Auto-driven playback must not die
    // with an activity-scoped ViewModel.
    val playbackCoordinator: PlaybackCoordinator by lazy {
        PlaybackCoordinator(
            playbackController = playbackController,
            libraryRepository = libraryRepository,
            queueRepository = queueRepository,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    // Playback history (M17): an append-only event log sharing the
    // app-lifetime DB. It is the ONLY writer of history — the UI and Android
    // Auto both read it, neither creates a second copy.
    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(
            historyDao = database.historyDao(),
            scope = applicationScope,
        )
    }

    // One tracker for the whole application, observing the same shared
    // PlaybackController the UI observes. Started eagerly so listening time is
    // accounted even when the user is not on the Home screen (e.g. Auto
    // playback or a backgrounded session).
    val historyTracker: HistoryTracker by lazy {
        HistoryTracker(
            repository = historyRepository,
            scope = applicationScope,
        ).also { tracker ->
            tracker.start(playbackController)
        }
    }

    // Android Auto boundary (M14): read-only browse tree over the existing
    // repositories, and a playback gateway that delegates to the shared
    // coordinator. Both are pure adapters — they own no state.
    val autoPlaybackBridge: AutoPlaybackBridge by lazy {
        AutoPlaybackGateway(
            coordinator = playbackCoordinator,
            favoritesRepository = favoritesRepository,
            playlistRepository = playlistRepository,
            historyRepository = historyRepository,
        )
    }

    val autoBrowseTree: AutoBrowseTreeProvider by lazy {
        AutoBrowseTree(
            favoritesRepository = favoritesRepository,
            playlistRepository = playlistRepository,
            historyRepository = historyRepository,
        )
    }

    val lyricsProvider by lazy {
        com.naudio.provider.lrclib.LrclibLyricsProvider(networkClient)
    }

    val lyricsRepository by lazy {
        com.naudio.data.repository.LyricsRepository(lyricsProvider)
    }
}
