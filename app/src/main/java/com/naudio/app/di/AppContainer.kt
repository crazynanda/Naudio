package com.naudio.app.di

import android.content.Context
import com.naudio.core.player.MediaControllerPlaybackController
import com.naudio.core.player.PlaybackController
import com.naudio.data.provider.ProviderRegistry
import com.naudio.data.repository.LibraryRepository
import com.naudio.provider.default.DefaultMetadataProvider
import com.naudio.provider.default.DefaultPlaybackProvider

/**
 * Manual, provider-based DI. Deliberately not Hilt at this milestone:
 * the graph is small and explicit construction documents the architecture.
 * When the graph grows (database, online providers), revisit.
 */
class AppContainer(context: Context) {

    // Providers: registration order = default priority.
    private val metadataProviders = listOf(
        DefaultMetadataProvider(),
    )

    private val playbackProviders = listOf(
        DefaultPlaybackProvider(),
    )

    val providerRegistry = ProviderRegistry(metadataProviders, playbackProviders)

    val libraryRepository = LibraryRepository(providerRegistry)

    // App-lifetime playback controller; the service owns the real player.
    val playbackController: PlaybackController by lazy {
        MediaControllerPlaybackController(context.applicationContext)
    }
}
