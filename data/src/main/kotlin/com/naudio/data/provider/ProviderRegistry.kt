package com.naudio.data.provider

import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the active metadata provider. The app talks to exactly one active
 * provider; switching providers is a data-layer concern, not a UI one.
 */
class ProviderRegistry(providers: List<MetadataProvider>) {

    private val registered: List<MetadataProvider> = providers.toList()

    private val providersById: Map<ProviderId, MetadataProvider> =
        registered.associateBy { it.id }

    private val _active = MutableStateFlow(providers.firstOrNull())

    /** The currently active metadata provider, or null if the registry is empty. */
    val activeProvider: MetadataProvider?
        get() = _active.value

    /** Emits the active provider whenever it changes (null = none active). */
    val active: Flow<MetadataProvider?> = _active.asStateFlow()

    /**
     * Switch the active provider. Returns false (and keeps the current
     * active provider) if [id] is not registered.
     */
    fun activate(id: ProviderId): Boolean {
        val provider = providersById[id] ?: return false
        _active.update { provider }
        return true
    }

    /** All registered providers in registration order. */
    fun all(): List<MetadataProvider> = registered.toList()
}
