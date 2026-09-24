package com.naudio.provider.default

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.provider.api.PlaybackProvider
import com.naudio.provider.api.ProviderId

/**
 * Offline, local-only playback provider. Resolves the bundled test asset and
 * nothing else: online providers, stream extraction and unofficial APIs are
 * later milestones.
 */
class DefaultPlaybackProvider : PlaybackProvider {

    override val id: ProviderId = ProviderId.Default

    override suspend fun resolve(track: Track): AudioSource? =
        when (track.id) {
            TEST_TRACK_ID -> AudioSource.Local(TEST_ASSET_URI)
            else -> null
        }

    companion object {
        /** Id of the canned demo track served by [DefaultMetadataProvider]. */
        const val TEST_TRACK_ID = "local-test"

        /** ExoPlayer-recognizable asset URI for the bundled tone. */
        const val TEST_ASSET_URI = "asset:///test_tone.wav"
    }
}
