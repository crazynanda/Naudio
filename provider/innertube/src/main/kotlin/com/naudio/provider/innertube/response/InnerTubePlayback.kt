package com.naudio.provider.innertube.response

/**
 * What the InnerTube playback-resolution endpoint said about one video.
 *
 * This is a small, honest, JSON-free value, and it is the complete extent of
 * what this backend is willing to learn about playback. In particular there is
 * no cipher blob, no signature, no player-script reference and no token field
 * here — see [directAudioUrl] for why.
 *
 * Internal: it describes a backend response, not a domain result.
 */
internal data class InnerTubePlayback(
    /** Raw `playabilityStatus.status` string, e.g. `OK`, `LOGIN_REQUIRED`. */
    val status: String,
    /**
     * A stream URL the backend handed over verbatim and that needs no further
     * processing, or null.
     *
     * Non-null ONLY when the response's `playabilityStatus.status` is `OK` AND
     * the best audio format carried a plain `url` field. Formats that carry
     * `signatureCipher`/`cipher` instead are ignored entirely: producing a
     * playable URL out of those would require deciphering YouTube's
     * anti-extraction scheme, which this module does not do under any
     * circumstance.
     */
    val directAudioUrl: String?,
)
