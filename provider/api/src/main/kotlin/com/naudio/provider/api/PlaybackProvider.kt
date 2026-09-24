package com.naudio.provider.api

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track

/**
 * Playback contract: resolves playable source handles for tracks. The actual
 * playback orchestration (service, session, ExoPlayer) lives in :core:player;
 * a provider must never own or reference a player.
 */
interface PlaybackProvider {
    /** Stable identifier of this provider, matching its [MetadataProvider] if it has one. */
    val id: ProviderId

    /**
     * Resolve the playable [AudioSource] for a track, or null if the provider
     * cannot play it. Cold and safe to call from any dispatcher.
     */
    suspend fun resolve(track: Track): AudioSource?
}
