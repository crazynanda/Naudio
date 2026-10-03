package com.naudio.provider.innertube.request

import com.naudio.provider.innertube.client.InnerTubeEndpoints
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Request construction. These tests pin the exact JSON each operation sends,
 * and — as importantly — pin the things it must never send.
 */
class InnerTubeRequestTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    // ------------------------------------------------------------------
    // Paths
    // ------------------------------------------------------------------

    @Test
    fun `each request targets its own endpoint`() {
        assertEquals(InnerTubeEndpoints.SEARCH, InnerTubeRequest.Search("q").path)
        assertEquals(InnerTubeEndpoints.BROWSE, InnerTubeRequest.Browse("UC1").path)
        assertEquals(InnerTubeEndpoints.NEXT, InnerTubeRequest.Next("vid").path)
        assertEquals(InnerTubeEndpoints.PLAYER, InnerTubeRequest.Player("vid").path)
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    @Test
    fun `first-page search carries the query and the songs filter`() {
        val body = parse(InnerTubeRequest.Search("rick astley").body())
        assertEquals("rick astley", body["query"]!!.jsonPrimitive.content)
        assertEquals(
            InnerTubeRequest.Search.SONGS_FILTER_PARAMS,
            body["params"]!!.jsonPrimitive.content,
        )
        assertFalse(body.containsKey("continuation"))
    }

    @Test
    fun `continuation search sends the token verbatim and drops the filter`() {
        val body = parse(InnerTubeRequest.Search("q", continuation = "CONT-TOKEN-1").body())
        assertEquals("CONT-TOKEN-1", body["continuation"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("params"))
    }

    // ------------------------------------------------------------------
    // Browse / Next
    // ------------------------------------------------------------------

    @Test
    fun `browse sends the browse id and omits absent optional fields`() {
        val body = parse(InnerTubeRequest.Browse("MPREb_Album").body())
        assertEquals("MPREb_Album", body["browseId"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("params"))
        assertFalse(body.containsKey("continuation"))
    }

    @Test
    fun `browse sends a continuation when one is supplied`() {
        val body = parse(InnerTubeRequest.Browse("UC1", continuation = "ARTIST-TOKEN-1").body())
        assertEquals("ARTIST-TOKEN-1", body["continuation"]!!.jsonPrimitive.content)
    }

    @Test
    fun `next sends the video id`() {
        val body = parse(InnerTubeRequest.Next("dQw4w9WgXcQ").body())
        assertEquals("dQw4w9WgXcQ", body["videoId"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // Player
    // ------------------------------------------------------------------

    @Test
    fun `player sends the video id and the content-check flags`() {
        val body = parse(InnerTubeRequest.Player("dQw4w9WgXcQ").body())
        assertEquals("dQw4w9WgXcQ", body["videoId"]!!.jsonPrimitive.content)
        assertEquals("true", body["contentCheckOk"]!!.jsonPrimitive.content)
        assertEquals("true", body["racyCheckOk"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // One client identity, for every endpoint
    // ------------------------------------------------------------------

    @Test
    fun `every endpoint sends the identical anonymous web client context`() {
        val bodies = listOf(
            InnerTubeRequest.Search("q"),
            InnerTubeRequest.Browse("UC1"),
            InnerTubeRequest.Next("vid"),
            InnerTubeRequest.Player("vid"),
        ).map { it.body() }

        val contexts = bodies.map { parse(it)["context"].toString() }.distinct()

        assertEquals(1, contexts.size, "endpoints must not diverge on client identity")
        val context = parse(bodies.first())["context"]!!.jsonObject
        val client = context["client"]!!.jsonObject
        assertEquals("WEB_REMIX", client["clientName"]!!.jsonPrimitive.content)
        assertEquals("1.20240403.01.00", client["clientVersion"]!!.jsonPrimitive.content)
        assertEquals("en", client["hl"]!!.jsonPrimitive.content)
        assertEquals("US", client["gl"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no request smuggles a second client identity to unlock a stream`() {
        val all = listOf(
            InnerTubeRequest.Search("q").body(),
            InnerTubeRequest.Browse("UC1").body(),
            InnerTubeRequest.Next("vid").body(),
            InnerTubeRequest.Player("vid").body(),
        )
        for (body in all) {
            // Identity switching per endpoint is the escape hatch this module
            // must not have: a mobile/tv/embedded client is never impersonated.
            for (spoofed in listOf("ANDROID_MUSIC", "ANDROID", "IOS", "TVHTML5", "WEB", "MWEB")) {
                assertFalse(body.contains("\"$spoofed\""), "request impersonates $spoofed")
            }
            assertFalse(body.contains("User-Agent"), "request overrides the user agent")
            assertFalse(body.contains("Authorization"), "request carries credentials")
            assertFalse(body.contains("SAPISIDHASH"), "request carries credentials")
            assertFalse(body.contains("visitorData"), "request carries a visitor token")
        }
    }

    @Test
    fun `no request contains protection-evasion fields`() {
        val all = listOf(
            InnerTubeRequest.Search("q").body(),
            InnerTubeRequest.Browse("UC1").body(),
            InnerTubeRequest.Next("vid").body(),
            InnerTubeRequest.Player("vid").body(),
        )
        for (body in all) {
            assertFalse(body.contains("poToken"), "request carries a PO token")
            assertFalse(body.contains("serviceIntegrityDimensions"), "request carries a PO token")
            assertFalse(body.contains("playbackContext"), "player request asks for deprotection help")
            assertFalse(body.contains("signatureTimestamp"), "player request asks for deprotection help")
            assertFalse(body.contains("contentCheckOk_rn"), "unexpected playback context")
        }
    }

    @Test
    fun `the player request carries nothing but the video id and the shared context`() {
        val body = parse(InnerTubeRequest.Player("vid").body())
        assertEquals(
            setOf("videoId", "contentCheckOk", "racyCheckOk", "context"),
            body.keys,
            "the player body must stay minimal and auditable",
        )
    }
}