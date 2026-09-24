package com.naudio.core.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateMapperTest {

    // Media3 Player.STATE_* constants (literals keep these tests pure JVM).
    @Test
    fun `statusOf maps all media3 playback states`() {
        assertEquals(PlaybackStatus.IDLE, PlayerStateMapper.statusOf(1)) // STATE_IDLE
        assertEquals(PlaybackStatus.BUFFERING, PlayerStateMapper.statusOf(2)) // STATE_BUFFERING
        assertEquals(PlaybackStatus.READY, PlayerStateMapper.statusOf(3)) // STATE_READY
        assertEquals(PlaybackStatus.ENDED, PlayerStateMapper.statusOf(4)) // STATE_ENDED
    }

    @Test
    fun `statusOf maps unexpected values to IDLE`() {
        assertEquals(PlaybackStatus.IDLE, PlayerStateMapper.statusOf(0))
        assertEquals(PlaybackStatus.IDLE, PlayerStateMapper.statusOf(-1))
        assertEquals(PlaybackStatus.IDLE, PlayerStateMapper.statusOf(99))
    }

    @Test
    fun `errorOf produces ERROR state with message`() {
        val state = PlayerStateMapper.errorOf("boom")

        assertEquals(PlaybackStatus.ERROR, state.status)
        assertEquals("boom", state.errorMessage)
        assertFalse(state.isPlaying)
    }

    @Test
    fun `isSeekable requires positive duration`() {
        assertTrue(PlayerState(durationMs = 3_000).isSeekable)
        assertFalse(PlayerState(durationMs = 0).isSeekable)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PositionTickerTest {

    @Test
    fun `emits ticks while playing and stops when paused`() = runTest {
        val ticker = PositionTicker(intervalMs = 100)
        val playing = MutableStateFlow(true)
        var ticks = 0

        backgroundScope.launch {
            ticker.ticks(playing).collect { ticks++ }
        }

        advanceTimeBy(350)
        runCurrent()
        // First tick immediate, then one per interval: t=0,100,200,300.
        assertEquals(4, ticks)

        playing.value = false
        advanceTimeBy(500)
        runCurrent()
        assertEquals(4, ticks)
    }

    @Test
    fun `no ticks when never playing`() = runTest {
        val ticker = PositionTicker(intervalMs = 100)
        var ticks = 0

        backgroundScope.launch {
            ticker.ticks(MutableStateFlow(false)).collect { ticks++ }
        }

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, ticks)
    }

    @Test
    fun `resumes ticking after play resumes`() = runTest {
        val ticker = PositionTicker(intervalMs = 100)
        val playing = MutableStateFlow(false)
        var ticks = 0

        backgroundScope.launch {
            ticker.ticks(playing).collect { ticks++ }
        }

        advanceTimeBy(300)
        runCurrent()
        assertEquals(0, ticks)

        playing.value = true
        advanceTimeBy(250)
        runCurrent()
        assertEquals(3, ticks) // t=0,100,200 after resume
    }
}
