package com.naudio.app.di

import android.content.Context
import com.naudio.core.database.NaudioDatabase
import com.naudio.core.network.NaudioHttpClient
import com.naudio.core.player.MediaControllerPlaybackController
import com.naudio.core.player.PlaybackController
import com.naudio.data.provider.ProviderRegistry
import com.naudio.data.repository.FavoritesRepository
import com.naudio.data.repository.LibraryRepository
import com.naudio.provider.api.ProviderId
import com.naudio.provider.default.DefaultMetadataProvider
import com.naudio.provider.default.DefaultPlaybackProvider
import com.naudio.provider.itunes.ItunesMetadataProvider
import com.naudio.provider.local.LocalMetadataProvider
import com.naudio.provider.local.LocalPlaybackProvider
import io.ktor.client.HttpClient

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

    // Providers: registration order = default priority. The local provider
    // stays first (default); online providers augment the catalog. iTunes is
    // metadata-only: there is deliberately no iTunes PlaybackProvider.
    private val metadataProviders = listOf(
        DefaultMetadataProvider(),
        ItunesMetadataProvider(networkClient),
        LocalMetadataProvider(applicationContext),
    )

    private val playbackProviders = listOf(
        DefaultPlaybackProvider(),
        LocalPlaybackProvider(),
    )

    val providerRegistry = ProviderRegistry(metadataProviders, playbackProviders).apply {
        // Milestone 6 app configuration: iTunes stays the initially active
        // catalog source; the user can switch to "Local Device" from the Home
        // selector, which re-drives search and source resolution through the
        // same registry. Playback stays independent: DefaultPlaybackProvider
        // is untouched and iTunes deliberately has no playback provider.
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

    // App-lifetime playback controller; the service owns the real player.
    val playbackController: PlaybackController by lazy {
        MediaControllerPlaybackController(applicationContext)
    }
}
