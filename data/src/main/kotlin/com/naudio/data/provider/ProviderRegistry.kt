package com.naudio.data.provider

import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.PlaybackProvider
import com.naudio.provider.api.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds registered providers and tracks the active metadata provider. The app
 * talks to exactly one active metadata provider; switching providers is a
 * data-layer concern, not a UI one. Playback providers are registered for
 * source resolution (metadata and playback remain separate capabilities).
 */
class ProviderRegistry(
    providers: List<MetadataProvider>,
    playbackProviders: List<PlaybackProvider> = emptyList(),
) {

    private val registered: List<MetadataProvider> = providers.toList()

    private val registeredPlayback: List<PlaybackProvider> = playbackProviders.toList()

    private val providersById: Map<ProviderId, MetadataProvider> =
        registered.associateBy { it.id }

    private val playbackProvidersById: Map<ProviderId, PlaybackProvider> =
        registeredPlayback.associateBy { it.id }

    private val _active = MutableStateFlow(providers.firstOrNull())

    /** The currently active metadata provider, or null if the registry is empty. */
    val activeProvider: MetadataProvider?
        get() = _active.value

    /** Emits the active provider whenever it changes (null = none active). */
    val active: Flow<MetadataProvider?> = _active.asStateFlow()

    /**
     * Switch the active metadata provider. Returns false (and keeps the
     * current active provider) if [id] is not registered.
     */
    fun activate(id: ProviderId): Boolean {
        val provider = providersById[id] ?: return false
        _active.update { provider }
        return true
    }

    /**
     * Resolve the playback provider for [id], or null if not registered.
     * Exact-match only: playback routing follows the id of the provider that
     * produced a track, so no fallback to the active metadata provider — a
     * track must never be played by a different provider's playback
     * capability just because that provider is currently selected.
     */
    fun playbackProvider(id: ProviderId): PlaybackProvider? =
        playbackProvidersById[id]

    /** All registered metadata providers in registration order. */
    fun all(): List<MetadataProvider> = registered.toList()

    /** All registered playback providers in registration order. */
    fun allPlayback(): List<PlaybackProvider> = registeredPlayback.toList()
}
