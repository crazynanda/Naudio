package com.naudio.data.repository

import com.naudio.core.model.AlbumDetail
import com.naudio.core.model.AudioSource
import com.naudio.core.model.ArtistDetail
import com.naudio.core.model.Track
import com.naudio.data.provider.ProviderRegistry
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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
     * instead of crashing the stream. Empty results are not errors.
     */
    fun search(query: Flow<String>): Flow<LibraryQueryState> =
        combine(query, registry.active) { q, provider -> q to provider }
            .distinctUntilChanged()
            .flatMapLatest { (q, provider) ->
                when {
                    q.isBlank() -> flowOf(LibraryQueryState.Idle)
                    provider == null -> flowOf(LibraryQueryState.Error("No provider available"))
                    else -> flow {
                        emit(provider.searchTracks(q).items)
                    }.map<List<Track>, LibraryQueryState> { LibraryQueryState.Results(it) }
                        .catch { emit(LibraryQueryState.Error(it.message ?: "Search failed")) }
                }
            }

    /**
     * Resolve the playable source for [track] through the playback provider
     * registered for [Track.providerId] — the provider that produced the
     * track, never whichever metadata provider happens to be active.
     * Null when no playback provider is registered for that id (metadata-only
     * providers) or when the provider itself cannot resolve the track.
     * Never throws for "unresolvable"; resolution failures degrade to null.
     */
    suspend fun resolveSource(track: Track): AudioSource? =
        registry.playbackProvider(ProviderId(track.providerId))?.resolve(track)

    /**
     * Resolve one artist page through the metadata provider that owns [providerId]
     * (M21).
     *
     * Routing is by [providerId] only — never by the active provider — because a
     * catalog entity belongs to exactly one provider and only that provider knows
     * its own identifier scheme. A provider that does not implement catalog
     * detail (the [MetadataProvider] default) or is not registered at all yields
     * null, which is a normal "not available", not a failure.
     *
     * Never throws for "unavailable"; transport failures propagate, exactly as
     * for [resolveSource].
     */
    suspend fun artist(providerId: String, artistId: String): ArtistDetail? =
        registry.metadataProvider(ProviderId(providerId))?.getArtist(artistId)

    /**
     * Resolve one album page through the metadata provider that owns [providerId]
     * (M21). Same routing and failure semantics as [artist].
     */
    suspend fun album(providerId: String, albumId: String): AlbumDetail? =
        registry.metadataProvider(ProviderId(providerId))?.getAlbum(albumId)
}
