package com.naudio.data.repository

import com.naudio.core.model.Track
import com.naudio.data.provider.ProviderRegistry
import com.naudio.provider.api.MetadataProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** UI-facing state of a library query, independent of which provider served it. */
sealed interface LibraryQueryState {
    data object Idle : LibraryQueryState
    data object Loading : LibraryQueryState
    data class Results(val tracks: List<Track>) : LibraryQueryState
    data class Error(val message: String) : LibraryQueryState
}

/**
 * Single entry point from the presentation layer into provider-backed data.
 * Knows nothing about playback: that separation is enforced by the provider API.
 */
class LibraryRepository(
    private val registry: ProviderRegistry,
) {

    /** Emits the display name of the active provider (null = none). */
    fun activeProviderName(): Flow<String?> =
        registry.active.map { provider -> provider?.displayName }.distinctUntilChanged()

    /**
     * Reactive search across the active provider. Re-subscribes whenever the
     * active provider changes; a provider error degrades to [LibraryQueryState.Error]
     * instead of crashing the stream.
     */
    fun search(query: Flow<String>): Flow<LibraryQueryState> =
        combine(query, registry.active) { q, provider -> q to provider }
            .distinctUntilChanged()
            .flatMapLatest { (q, provider) ->
                when {
                    q.isBlank() -> flowOf(LibraryQueryState.Idle)
                    provider == null -> flowOf(LibraryQueryState.Error("No provider available"))
                    else -> provider.search(q)
                        .map<List<Track>, LibraryQueryState> { LibraryQueryState.Results(it) }
                        .catch { emit(LibraryQueryState.Error(it.message ?: "Search failed")) }
                }
            }
}
