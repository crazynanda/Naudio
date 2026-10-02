package com.naudio.core.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M16: playback-mode state and the pure repeat-cycle mapping.
 *
 * Runs on the JVM: Media3's `REPEAT_MODE_*` values are compile-time int
 * constants, so no Android runtime is required.
 */
class PlaybackModeStateTest {

    // ------------------------------------------------------------------
    // defaults
    // ------------------------------------------------------------------

    @Test
    fun `default state is not shuffled and repeat is off`() {
        val state = PlayerState()

        assertFalse(state.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_OFF, state.repeatMode)
        assertEquals(0L, state.repeatLoopCount)
    }

    @Test
    fun `default state helpers report off`() {
        val state = PlayerState()

        assertTrue(state.isRepeatOff)
        assertFalse(state.isRepeatOne)
        assertFalse(state.isRepeatAll)
    }

    // ------------------------------------------------------------------
    // helper properties reflect the actual mode
    // ------------------------------------------------------------------

    @Test
    fun `isRepeatOne reflects REPEAT_MODE_ONE`() {
        val state = PlayerState(repeatMode = Player.REPEAT_MODE_ONE)

        assertTrue(state.isRepeatOne)
        assertFalse(state.isRepeatOff)
        assertFalse(state.isRepeatAll)
    }

    @Test
    fun `isRepeatAll reflects REPEAT_MODE_ALL`() {
        val state = PlayerState(repeatMode = Player.REPEAT_MODE_ALL)

        assertTrue(state.isRepeatAll)
        assertFalse(state.isRepeatOff)
        assertFalse(state.isRepeatOne)
    }

    @Test
    fun `an unknown repeat mode is treated as off`() {
        val state = PlayerState(repeatMode = 99)

        assertTrue(state.isRepeatOff)
        assertFalse(state.isRepeatOne)
        assertFalse(state.isRepeatAll)
    }

    @Test
    fun `shuffle state is carried on the state`() {
        assertTrue(PlayerState(shuffleModeEnabled = true).shuffleModeEnabled)
        assertFalse(PlayerState(shuffleModeEnabled = false).shuffleModeEnabled)
    }

    @Test
    fun `repeat loop count is carried on the state`() {
        assertEquals(3L, PlayerState(repeatLoopCount = 3L).repeatLoopCount)
    }

    // ------------------------------------------------------------------
    // repeat cycling: OFF -> ALL -> ONE -> OFF
    // ------------------------------------------------------------------

    @Test
    fun `cycle goes OFF to ALL`() {
        assertEquals(
            Player.REPEAT_MODE_ALL,
            PlayerStateMapper.nextRepeatMode(Player.REPEAT_MODE_OFF),
        )
    }

    @Test
    fun `cycle goes ALL to ONE`() {
        assertEquals(
            Player.REPEAT_MODE_ONE,
            PlayerStateMapper.nextRepeatMode(Player.REPEAT_MODE_ALL),
        )
    }

    @Test
    fun `cycle goes ONE to OFF`() {
        assertEquals(
            Player.REPEAT_MODE_OFF,
            PlayerStateMapper.nextRepeatMode(Player.REPEAT_MODE_ONE),
        )
    }

    @Test
    fun `full cycle returns to the starting mode`() {
        var mode = Player.REPEAT_MODE_OFF
        repeat(3) { mode = PlayerStateMapper.nextRepeatMode(mode) }

        assertEquals(Player.REPEAT_MODE_OFF, mode)
    }

    @Test
    fun `cycle visits all three modes exactly once`() {
        val visited = buildList {
            var mode = Player.REPEAT_MODE_OFF
            repeat(3) {
                add(mode)
                mode = PlayerStateMapper.nextRepeatMode(mode)
            }
        }

        assertEquals(
            listOf(
                Player.REPEAT_MODE_OFF,
                Player.REPEAT_MODE_ALL,
                Player.REPEAT_MODE_ONE,
            ),
            visited,
        )
    }

    @Test
    fun `cycle from an unknown mode falls back to OFF`() {
        assertEquals(
            Player.REPEAT_MODE_OFF,
            PlayerStateMapper.nextRepeatMode(99),
        )
    }

    // ------------------------------------------------------------------
    // existing state mapping is unaffected
    // ------------------------------------------------------------------

    @Test
    fun `status mapping still works with the new fields present`() {
        assertEquals(PlaybackStatus.READY, PlayerStateMapper.statusOf(3))
        assertEquals(PlaybackStatus.BUFFERING, PlayerStateMapper.statusOf(2))
    }
}
