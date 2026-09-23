package com.naudio.app.di

import android.content.Context
import com.naudio.data.provider.ProviderRegistry
import com.naudio.data.repository.LibraryRepository
import com.naudio.provider.default.DefaultMetadataProvider

/**
 * Manual, provider-based DI. Deliberately not Hilt at this milestone:
 * the graph is small and explicit construction documents the architecture.
 * When the graph grows (database, player, online providers), revisit.
 */
class AppContainer(context: Context) {

    // Providers: registration order = default priority.
    private val metadataProviders = listOf(
        DefaultMetadataProvider(),
    )

    val providerRegistry = ProviderRegistry(metadataProviders)

    val libraryRepository = LibraryRepository(providerRegistry)
}
