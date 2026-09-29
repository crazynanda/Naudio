package com.naudio.data.repository

import com.naudio.core.model.Track

/**
 * Provider-aware identity of a [Track]: (providerId, trackId). The favorites
 * store keys its rows by exactly this composite identity (see [TrackEntity]'s
 * composite primary key), so the same numeric id from two different providers
 * never collides. UI layers use [TrackKey] for O(1) favorite-state lookups of
 * the current playback target without threading DAO types around.
 */
data class TrackKey(val providerId: String, val trackId: String) {

    /** Identity of [track] as stored by the favorites repository. */
    constructor(track: Track) : this(providerId = track.providerId, trackId = track.id)
}
