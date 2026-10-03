package com.naudio.provider.innertube.parser

import com.naudio.provider.innertube.parser.InnerTubeJson.array
import com.naudio.provider.innertube.parser.InnerTubeJson.obj
import com.naudio.provider.innertube.parser.InnerTubeJson.str
import com.naudio.provider.innertube.response.InnerTubePlayback

/**
 * Parses the InnerTube playback-resolution response into an honest
 * [InnerTubePlayback].
 *
 * ## What this parser reads
 * `playabilityStatus.status`, and the highest-bitrate audio format's plain
 * `url` field.
 *
 * ## What this parser deliberately does NOT do
 *
 * This is the single most important boundary in the module, so it is spelled
 * out rather than implied:
 *
 *  - **No stream deprotection.** Formats whose URL arrives as
 *    `signatureCipher`/`cipher` are skipped entirely. Turning those into a
 *    playable URL requires running YouTube's player JS to extract and apply the
 *    media-signing transform — an anti-extraction control. This module never
 *    fetches, evaluates or embeds player scripts, and never calls a deciphering
 *    endpoint. Such formats are treated exactly like "no format at all".
 *
 *  - **No access-control evasion.** The request is sent anonymously with the
 *    same public web-client identity used for every other endpoint (see
 *    `InnerTubeContext`); there is no per-endpoint client switching, no
 *    user-agent spoofing, no PO token, and no attempt to make a non-entitled
 *    response look entitled. A `LOGIN_REQUIRED` / `UNPLAYABLE` /
 *    `ERROR` / `AGE_VERIFICATION_REQUIRED` status is reported faithfully and
 *    becomes "no source", not a problem to work around.
 *
 *  - **No URL synthesis.** [InnerTubePlayback.directAudioUrl] is only ever a
 *    string the server sent in a `url` field. Nothing is constructed,
 *    re-signed, or assembled from fragments.
 *
 * The result: when YouTube serves a directly-playable anonymous stream, this
 * reports it; when it does not — which is the common case, because most
 * catalog audio is served through `signatureCipher` — it reports no source and
 * Naudio surfaces its normal "unavailable" state.
 */
internal object InnerTubePlayerParser {

    /** The only status under which a URL is treated as usable. */
    private const val STATUS_OK = "OK"

    fun parse(body: String): InnerTubePlayback {
        val root = InnerTubeJson.parseObject(body)
            ?: return InnerTubePlayback(status = STATUS_UNKNOWN, directAudioUrl = null)
        val status = root.obj("playabilityStatus")?.str("status") ?: STATUS_UNKNOWN
        return InnerTubePlayback(
            status = status,
            directAudioUrl = directAudioUrl(root, status),
        )
    }

    /**
     * The best directly-playable audio URL, or null.
     *
     * Null in ALL of these cases, by design: the status is not `OK`; there is
     * no `streamingData`; there is no audio format; the best audio formats are
     * ciphered; the winning format has no `url` field.
     */
    private fun directAudioUrl(root: kotlinx.serialization.json.JsonObject, status: String): String? {
        if (status != STATUS_OK) return null
        val formats = root.obj("streamingData")?.array("adaptiveFormats").orEmpty()

        var bestUrl: String? = null
        var bestBitrate = Long.MIN_VALUE
        for (format in formats) {
            val mimeType = format.str("mimeType").orEmpty()
            if (!mimeType.startsWith("audio/")) continue
            // Ciphered formats carry no usable url — skipping them here is what
            // keeps this module out of stream-deprotection business entirely.
            val url = format.str("url") ?: continue
            val bitrate = format.str("averageBitrate")?.toLongOrNull()
                ?: format.str("bitrate")?.toLongOrNull()
                ?: 0L
            if (bitrate > bestBitrate) {
                bestUrl = url
                bestBitrate = bitrate
            }
        }
        return bestUrl
    }

    private const val STATUS_UNKNOWN = "UNKNOWN"
}