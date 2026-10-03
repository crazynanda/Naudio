package com.naudio.provider.innertube.client

/**
 * The InnerTube endpoint paths this module is allowed to call.
 *
 * Internal by design: these strings are HTTP knowledge and must never escape the
 * backend. Callers of [com.naudio.provider.innertube.api.YtMusicBackend] ask for
 * "a search" or "an album", never for a URL.
 */
internal object InnerTubeEndpoints {

    /** Origin of the anonymous, public YouTube Music web client. */
    const val BASE_URL: String = "https://music.youtube.com"

    /** Songs-filtered catalog search. */
    const val SEARCH: String = "/youtubei/v1/search"

    /** Channel, album, playlist and home browsing. */
    const val BROWSE: String = "/youtubei/v1/browse"

    /** Watch page: single-song metadata and the related-track rail. */
    const val NEXT: String = "/youtubei/v1/next"

    /**
     * Playback-resolution metadata. Called anonymously with the SAME public web
     * client identity as every other endpoint here — this module never switches
     * client identity per endpoint to obtain a different (e.g. un-ciphered)
     * stream shape. See `InnerTubePlayerParser` for what is and is not read out
     * of the response.
     */
    const val PLAYER: String = "/youtubei/v1/player"
}
