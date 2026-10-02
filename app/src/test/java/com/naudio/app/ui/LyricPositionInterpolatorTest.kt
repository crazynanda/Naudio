package com.naudio.app.ui

import com.naudio.core.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure lyrics interpolation maths
 * ([LyricPositionInterpolator], [findActiveLineIndex]).
 *
 * These are plain JVM tests: neither helper imports Compose or Android, and no
 * frame clock or 60 FPS timer is involved — frame time is passed in directly.
 */
class LyricPositionInterpolatorTest {

    private fun lines(vararg timesMs: Long): List<LyricLine> =
        timesMs.map { LyricLine(it, "line $it") }

    // ------------------------------------------------------------------
    // 1-2. playing interpolation
    // ------------------------------------------------------------------

    @Test
    fun `playing position advances from the anchor by elapsed frame time`() {
        val interpolator = LyricPositionInterpolator()
        interpolator.reanchor(positionMs = 10_000L, isPlaying = true, frameTimeMs = 0L)

        // 250 ms of frame time after the anchor.
        assertEquals(10_250L, interpolator.positionAt(250L))
    }

    @Test
    fun `playing position advances by the full elapsed frame time`() {
        val interpolator = LyricPositionInterpolator()
        interpolator.reanchor(positionMs = 10_000L, isPlaying = true, frameTimeMs = 0L)

        // 500 ms later: the new authoritative sample has not arrived yet.
        assertEquals(10_500L, interpolator.positionAt(500L))
    }

    @Test
    fun `interpolation is continuous from a non-zero anchor frame time`() {
        val interpolator = LyricPositionInterpolator()
        interpolator.reanchor(positionMs = 10_000L, isPlaying = true, frameTimeMs = 1_000L)

        assertEquals(10_250L, interpolator.positionAt(1_250L))
    }

    // ------------------------------------------------------------------
    // 3. paused
    // ------------------------------------------------------------------

    @Test
    fun `paused position does not advance with frame time`() {
        val interpolator = LyricPositionInterpolator()
        interpolator.reanchor(positionMs = 10_000L, isPlaying = false, frameTimeMs = 0L)

        // 500 ms of frame time later the visual position is still the anchor.
        assertEquals(10_000L, interpolator.positionAt(500L))
        assertEquals(10_000L, interpolator.positionAt(5_000L))
    }

    @Test
    fun `pause after playing freezes at the authoritative position`() {
        val interpolator = LyricPositionInterpolator()
        interpolator.reanchor(positionMs = 10_000L, isPlaying = true, frameTimeMs = 0L)
        assertEquals(10_500L, interpolator.positionAt(500L))

        // The authoritative sample shows the player paused at 12_400.
        interpolator.reanchor(positionMs = 12_400L, isPlaying = false, frameTimeMs = 500L)
        assertEquals(12_400L, interpolator.positionAt(900L))
    }

    // ------------------------------------------------------------------
    // 4. forward seek
    // ------------------------------------------------------------------

    @Test
    fun `forward seek re-anchors at the new position`() {
        val interpolator = LyricPositionInterpolator()
        interpolator.reanchor(positionMs = 30_000L, isPlaying = true, frameTimeMs = 0L)
        assertEquals(30_500L, interpolator.positionAt(500L))

        // User seeks forward to 90s; the next authoritative sample wins
        // immediately rather than continuing from 30s + elapsed.
        interpolator.reanchor(positionMs = 90_000L, isPlaying = true, frameTimeMs = 500L)

        assertEquals(90_000L, interpolator.positionAt(500L))
        assertEquals(90_100L, interpolator.positionAt(600L))
    }

    // ------------------------------------------------------------------
    // 5. backward seek
    // ------------------------------------------------------------------

    @Test
    fun `backward seek re-anchors at the new position`() {
        val interpolator = LyricPositionInterpolator()
        interpolator.reanchor(positionMs = 90_000L, isPlaying = true, frameTimeMs = 0L)
        assertEquals(90_500L, interpolator.positionAt(500L))

        // User seeks back to 20s.
        interpolator.reanchor(positionMs = 20_000L, isPlaying = true, frameTimeMs = 500L)

        assertEquals(20_000L, interpolator.positionAt(500L))
        assertEquals(20_100L, interpolator.positionAt(600L))
    }

    @Test
    fun `seek is corrected even when the previous clock had run far ahead`() {
        val interpolator = LyricPositionInterpolator()
        interpolator.reanchor(positionMs = 10_000L, isPlaying = true, frameTimeMs = 0L)
        assertEquals(13_000L, interpolator.positionAt(3_000L))

        // A seek backwards must snap, not continue forward from 13s.
        interpolator.reanchor(positionMs = 5_000L, isPlaying = true, frameTimeMs = 3_000L)
        assertEquals(5_000L, interpolator.positionAt(3_000L))
    }

    // ------------------------------------------------------------------
    // 6. track change
    // ------------------------------------------------------------------

    @Test
    fun `reset clears the previous track interpolation`() {
        val interpolator = LyricPositionInterpolator()
        interpolator.reanchor(positionMs = 10_000L, isPlaying = true, frameTimeMs = 0L)
        assertEquals(10_500L, interpolator.positionAt(500L))

        interpolator.reset()

        assertEquals(0L, interpolator.authoritativePositionMs)
        assertEquals(0L, interpolator.anchorFrameTimeMs)
        assertEquals(false, interpolator.isInterpolating)
        assertEquals(0L, interpolator.positionAt(500L))
    }

    @Test
    fun `a new track does not inherit the previous track position`() {
        val interpolator = LyricPositionInterpolator()
        interpolator.reanchor(positionMs = 120_000L, isPlaying = true, frameTimeMs = 0L)
        val oldTrackVisual = interpolator.positionAt(2_000L)
        assertEquals(122_000L, oldTrackVisual)

        // Track change: reset, then anchor the new track from scratch.
        interpolator.reset()
        interpolator.reanchor(positionMs = 1_000L, isPlaying = true, frameTimeMs = 2_000L)

        assertEquals(1_000L, interpolator.positionAt(2_000L))
        assertEquals(1_500L, interpolator.positionAt(2_500L))
    }

    // ------------------------------------------------------------------
    // drift bounding
    // ------------------------------------------------------------------

    @Test
    fun `re-anchoring every poll bounds drift to one poll interval`() {
        val interpolator = LyricPositionInterpolator()
        // Authoritative samples arrive every 500 ms of frame time; each one
        // re-anchors. Drive a 4-second window at ~60 FPS and assert the visual
        // position never runs more than one poll interval ahead of player truth.
        var authoritative = 0L
        var nextPollFrame = 0L
        for (frame in 0L..4_000L step 16L) {
            if (frame >= nextPollFrame) {
                authoritative += 500L
                nextPollFrame += 500L
                interpolator.reanchor(authoritative, isPlaying = true, frameTimeMs = frame)
            }
            val visual = interpolator.positionAt(frame)
            val drift = visual - authoritative
            assertTrue("drift was $drift ms at frame $frame", drift in 0L..500L)
        }
    }

    // ------------------------------------------------------------------
    // 7. active line lookup (binary search)
    // ------------------------------------------------------------------

    @Test
    fun `active line before the first timestamp is none`() {
        assertEquals(-1, findActiveLineIndex(lines(1_000L, 2_000L), 999L))
    }

    @Test
    fun `active line exactly at the first timestamp is the first line`() {
        assertEquals(0, findActiveLineIndex(lines(1_000L, 2_000L), 1_000L))
    }

    @Test
    fun `active line between two timestamps is the earlier line`() {
        assertEquals(0, findActiveLineIndex(lines(1_000L, 2_000L), 1_999L))
    }

    @Test
    fun `active line exactly on a boundary is the later line`() {
        assertEquals(1, findActiveLineIndex(lines(1_000L, 2_000L), 2_000L))
    }

    @Test
    fun `active line after the final timestamp is the last line`() {
        assertEquals(2, findActiveLineIndex(lines(1_000L, 2_000L, 3_000L), 999_999L))
    }

    @Test
    fun `active line of an empty lyric list is none`() {
        assertEquals(-1, findActiveLineIndex(emptyList(), 5_000L))
    }

    @Test
    fun `active line of a single-line list`() {
        val single = lines(1_000L)
        assertEquals(-1, findActiveLineIndex(single, 999L))
        assertEquals(0, findActiveLineIndex(single, 1_000L))
        assertEquals(0, findActiveLineIndex(single, 500_000L))
    }

    @Test
    fun `active line uses the interpolated position to switch early`() {
        val lyrics = lines(1_000L, 2_000L)
        // At the authoritative 2_000 ms sample the second line is active...
        assertEquals(1, findActiveLineIndex(lyrics, 2_000L))
        // ...and an interpolated position just before it still shows the first.
        assertEquals(0, findActiveLineIndex(lyrics, 1_999L))
    }

    @Test
    fun `active line lookup is correct across a large lyric file`() {
        // 2000 lines: guards the binary search against an off-by-one.
        val lyrics = lines(*LongArray(2_000) { it * 1_000L })
        assertEquals(-1, findActiveLineIndex(lyrics, -5L))
        assertEquals(0, findActiveLineIndex(lyrics, 0L))
        assertEquals(0, findActiveLineIndex(lyrics, 999L))
        assertEquals(1, findActiveLineIndex(lyrics, 1_000L))
        assertEquals(1_999, findActiveLineIndex(lyrics, 1_999_000L))
        assertEquals(1_999, findActiveLineIndex(lyrics, 9_999_999L))
    }

    @Test
    fun `active line handles duplicate timestamps deterministically`() {
        val lyrics = listOf(
            LyricLine(1_000L, "first"),
            LyricLine(1_000L, "second"),
            LyricLine(2_000L, "third"),
        )
        // Binary search returns the last index at or before the position.
        assertEquals(1, findActiveLineIndex(lyrics, 1_000L))
        assertEquals(2, findActiveLineIndex(lyrics, 2_000L))
    }
}
