package com.naudio.provider.api

import com.naudio.core.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * Read-only contract for anything that can supply track metadata (search,
 * lookups, availability). Metadata and playback are deliberately separate
 * capabilities: implementations must never depend on a player.
 */
interface MetadataProvider {
    /** Stable identifier of this provider, used by the registry and UI. */
    val id: ProviderId

    /** Human-readable name shown in the UI. */
    val displayName: String

    /** Whether this provider can currently serve requests. */
    val isAvailable: Flow<Boolean>

    /**
     * Search tracks matching [query]. Never throws for "no results";
     * returns an empty flow/list instead. Implementations should be cold
     * and safe to call from any dispatcher.
     */
    fun search(query: String): Flow<List<Track>>

    /** Resolve full metadata for a single track, or null if unknown. */
    suspend fun lookup(trackId: String): Track?
}
