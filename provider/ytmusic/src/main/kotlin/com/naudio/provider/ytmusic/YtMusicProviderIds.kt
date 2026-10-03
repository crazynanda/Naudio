package com.naudio.provider.ytmusic

import com.naudio.provider.innertube.api.YtMusicBackend

/**
 * Stable identifiers for the YouTube Music provider.
 *
 * The id itself is owned by the backend boundary
 * ([YtMusicBackend.PROVIDER_ID]) because the backend stamps it onto every
 * [com.naudio.core.model.Track] it produces; re-exporting it here keeps one
 * source of truth for the metadata provider, the playback provider and the
 * tracks in between, while leaving this module free of InnerTube types.
 */
object YtMusicProviderIds {
    /** Provider id used for the metadata provider and every Track it emits. */
    const val YTMUSIC = YtMusicBackend.PROVIDER_ID

    /** Human-readable name shown in the UI. */
    const val DISPLAY_NAME = "YouTube Music"
}