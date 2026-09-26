package com.naudio.app.di

import android.content.Context
import com.naudio.core.database.NaudioDatabase
import com.naudio.core.network.NaudioHttpClient
import com.naudio.core.player.MediaControllerPlaybackController
import com.naudio.core.player.PlaybackController
import com.naudio.data.provider.ProviderRegistry
import com.naudio.data.repository.FavoritesRepository
import com.naudio.data.repository.LibraryRepository
import com.naudio.provider.default.DefaultMetadataProvider
import com.naudio.provider.default.DefaultPlaybackProvider
import io.ktor.client.HttpClient

/** Manual, provider-based DI. Deliberately not Hilt at this milestone:
 * the graph is small and explicit construction documents the architecture.
 * When the graph grows (database, online providers), revisit.
 */
class AppContainer(context: Context) {

    private val applicationContext = context.applicationContext

    // Providers: registration order = default priority.
    private val metadataProviders = listOf(
        DefaultMetadataProvider(),
    )

    private val playbackProviders = listOf(
        DefaultPlaybackProvider(),
    )

    val providerRegistry = ProviderRegistry(metadataProviders, playbackProviders)

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

    // Application-lifetime, shared Ktor HTTP client instance — created once,
    // reused for every request (never constructed per request).
    val networkClient: HttpClient by lazy {
        NaudioHttpClient.create()
    }
}
