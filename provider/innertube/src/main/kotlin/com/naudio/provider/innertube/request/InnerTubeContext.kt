package com.naudio.provider.innertube.request

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The one and only InnerTube request context this module ever sends.
 *
 * It is a single, anonymous, public-web-client identity, used verbatim by every
 * endpoint (search, browse, next, player). That uniformity is deliberate and is
 * a hard boundary, not an accident:
 *
 *  - There is exactly ONE client identity in the module. Nothing selects a
 *    different identity per endpoint to obtain a more permissive response.
 *  - The identity carries no credentials, cookies, OAuth token, PO token, or
 *    user agent override — it is the same anonymous context the public
 *    music.youtube.com site itself sends.
 *  - Because the same identity is used for catalog AND playback, the playback
 *    path receives exactly what the catalog path is entitled to. There is no
 *    second identity whose only purpose is to change the answer.
 *
 * Internal: the concrete client name/version is HTTP knowledge and never
 * crosses the [com.naudio.provider.innertube.api.YtMusicBackend] boundary.
 */
internal object InnerTubeContext {

    /** The public, anonymous web client identity. */
    private const val CLIENT_NAME = "WEB_REMIX"
    private const val CLIENT_VERSION = "1.20240403.01.00"
    private const val HL = "en"
    private const val GL = "US"

    /** The shared request context, identical for every endpoint. */
    fun build(): JsonObject = buildJsonObject {
        put(
            "client",
            buildJsonObject {
                put("clientName", CLIENT_NAME)
                put("clientVersion", CLIENT_VERSION)
                put("hl", HL)
                put("gl", GL)
            },
        )
    }
}
