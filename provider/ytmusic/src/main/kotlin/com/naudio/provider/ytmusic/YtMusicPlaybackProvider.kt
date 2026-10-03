package com.naudio.provider.ytmusic

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.provider.api.PlaybackProvider
import com.naudio.provider.api.ProviderId
import com.naudio.provider.innertube.api.YtMusicBackend

/**
 * Playback capability for the YouTube Music catalog, expressed as a thin
 * adapter over [YtMusicBackend.resolvePlayback].
 *
 * Like [YtMusicMetadataProvider] this class makes no network call and knows
 * nothing about how the backend obtains a stream — it owns no HTTP client, no
 * request builder and no response parser. All it does is route by
 * [Track.providerId] and hand back what the backend decided.
 *
 * Returning `null` is the expected, honest outcome whenever the backend cannot
 * legitimately serve an anonymous stream. [LibraryRepository.resolveSource]
 * reports that as "no source", and the playback coordinator surfaces its normal
 * unavailable state — exactly the same clean failure path a metadata-only
 * provider takes. No URL is fabricated and no error is swallowed.
 */
class YtMusicPlaybackProvider(
    private val backend: YtMusicBackend,
) : PlaybackProvider {

    override val id: ProviderId = ProviderId(YtMusicProviderIds.YTMUSIC)

    override suspend fun resolve(track: Track): AudioSource? = backend.resolvePlayback(track)
}