package com.naudio.provider.api

import com.naudio.core.model.Track

/**
 * Playback contract. Intentionally a marker at this milestone: the concrete
 * surface (prepare/play/pause/queue/position/error states) is defined when
 * :core:player is implemented in a later milestone. The metadata/playback
 * separation is established here architecturally.
 */
interface PlaybackProvider {
    /** Stable identifier of this provider, matching its [MetadataProvider] if it has one. */
    val id: ProviderId

    /**
     * Resolve the playable stream/source handle for a track, or null if the
     * provider cannot play it. Actual playback orchestration lives in
     * :core:player in a later milestone.
     */
    suspend fun resolve(track: Track): Track?
}
