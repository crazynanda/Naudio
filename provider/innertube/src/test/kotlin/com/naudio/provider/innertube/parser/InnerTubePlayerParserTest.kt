package com.naudio.provider.innertube.parser

import com.naudio.provider.innertube.Fixtures
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Playback-resolution parsing — the module's most important boundary.
 *
 * These tests pin both halves of the contract: a directly-playable URL the
 * server volunteered IS accepted, and nothing else is — in particular a
 * ciphered format is never deprotected, no matter how much of it is present.
 */
class InnerTubePlayerParserTest {

    @Test
    fun `an OK status with a direct audio url yields that url`() {
        val playback = InnerTubePlayerParser.parse(Fixtures.load("player_direct.json"))

        assertEquals("OK", playback.status)
        assertEquals("https://rr3---sn-direct-audio.example/mp4-m4a", playback.directAudioUrl)
    }

    @Test
    fun `the highest-bitrate AUDIO format wins and video formats are ignored`() {
        // player_direct.json: audio 251 @113337, audio 140 @129999, video 248 @113337.
        val playback = InnerTubePlayerParser.parse(Fixtures.load("player_direct.json"))

        assertTrue(playback.directAudioUrl!!.contains("mp4-m4a"))
        assertTrue(!playback.directAudioUrl!!.contains("webm-vp9"))
    }

    @Test
    fun `ciphered formats yield no url and are never deprotected`() {
        // The fixture carries full signatureCipher blobs. Decoding them would
        // require running YouTube's player script; this module never does, so the
        // answer is "no source" even though the status is OK.
        val playback = InnerTubePlayerParser.parse(Fixtures.load("player_ciphered.json"))

        assertEquals("OK", playback.status)
        assertNull(playback.directAudioUrl)
    }

    @Test
    fun `a format's verbatim url is honoured even when a cipher blob is also present`() {
        val playback = InnerTubePlayerParser.parse(
            """
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{"adaptiveFormats":[
               {"mimeType":"audio/mp4","bitrate":128000,
                "signatureCipher":"s=DEADBEEF&sp=sig&url=https%3A%2F%2Fciphered.example%2Fa",
                "url":"https://ciphered.example/a"}
             ]}}
            """,
        )

        // Only the server's own `url` field is read; the signatureCipher blob is
        // ignored entirely. Nothing was deciphered to get here.
        assertEquals("https://ciphered.example/a", playback.directAudioUrl)
    }

    @Test
    fun `a non-entitled status yields no url even when streams are present`() {
        val playback = InnerTubePlayerParser.parse(Fixtures.load("player_not_entitled.json"))

        assertEquals("LOGIN_REQUIRED", playback.status)
        assertNull(playback.directAudioUrl)
    }

    @Test
    fun `every non-OK status yields no url`() {
        for (status in listOf(
            "LOGIN_REQUIRED",
            "UNPLAYABLE",
            "ERROR",
            "AGE_VERIFICATION_REQUIRED",
            "AGE_CHECK_REQUIRED",
            "CONTENT_CHECK_REQUIRED",
            "LIVE_STREAM_OFFLINE",
        )) {
            val playback = InnerTubePlayerParser.parse(
                """
                {"playabilityStatus":{"status":"$status"},
                 "streamingData":{"adaptiveFormats":[
                   {"mimeType":"audio/mp4","bitrate":128000,"url":"https://blocked.example/a"}
                 ]}}
                """,
            )
            assertEquals(status, playback.status)
            assertNull(playback.directAudioUrl, "$status must not yield a source")
        }
    }

    @Test
    fun `a video-only stream list yields no url`() {
        val playback = InnerTubePlayerParser.parse(
            """
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{"adaptiveFormats":[
               {"mimeType":"video/mp4","bitrate":3000000,"url":"https://video.example/v"}
             ]}}
            """,
        )

        assertNull(playback.directAudioUrl)
    }

    @Test
    fun `a progressive-format only response yields no url`() {
        // Adaptive-only extraction is deliberate: `formats` (progressive
        // muxed audio+video) is not something this backend will hand over.
        val playback = InnerTubePlayerParser.parse(
            """
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{"formats":[
               {"mimeType":"video/mp4","bitrate":500000,"url":"https://video.example/progressive"}
             ]}}
            """,
        )

        assertNull(playback.directAudioUrl)
    }

    @Test
    fun `an OK response with no streaming data yields no url`() {
        val playback = InnerTubePlayerParser.parse("""{"playabilityStatus":{"status":"OK"}}""")

        assertEquals("OK", playback.status)
        assertNull(playback.directAudioUrl)
    }

    @Test
    fun `a malformed or unexpected body degrades to unknown rather than throwing`() {
        assertEquals("UNKNOWN", InnerTubePlayerParser.parse("").status)
        assertEquals("UNKNOWN", InnerTubePlayerParser.parse("""["nope"]""").status)
        assertEquals("UNKNOWN", InnerTubePlayerParser.parse("{}").status)
        assertNull(InnerTubePlayerParser.parse("{}").directAudioUrl)
    }

    @Test
    fun `an audio format without a bitrate still resolves`() {
        val playback = InnerTubePlayerParser.parse(
            """
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{"adaptiveFormats":[
               {"mimeType":"audio/mp4","url":"https://direct.example/a"}
             ]}}
            """,
        )

        assertEquals("https://direct.example/a", playback.directAudioUrl)
    }
}