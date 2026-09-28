package com.naudio.provider.local

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.provider.api.PlaybackProvider
import com.naudio.provider.api.ProviderId

/**
 * On-device playback provider: resolves a local [Track] to the MediaStore
 * content URI the existing Media3 stack already consumes
 * ([com.naudio.core.model.AudioSource.Local]). Pure mapping — no player is
 * created, referenced, or controlled here.
 */
class LocalPlaybackProvider : PlaybackProvider {

    override val id: ProviderId = ProviderId(LocalProviderIds.LOCAL)

    override suspend fun resolve(track: Track): AudioSource? {
        val id = track.id.toLongOrNull() ?: return null
        if (track.providerId != LocalProviderIds.LOCAL) return null
        return AudioSource.Local(trackContentUri(id))
    }
}
