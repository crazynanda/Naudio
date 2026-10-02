package com.naudio.app.history

import com.naudio.core.database.entity.HistoryEntity
import com.naudio.core.model.Track
import com.naudio.data.repository.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M17 acceptance tests for [HistoryTracker] — the sixteen scenarios from the
 * milestone brief plus the boundary and duplicate checks around them.
 *
 * The tests drive the REAL tracker and the REAL [HistoryRepository] over an
 * in-memory [FakeHistoryDao]: only the two clocks and the player are faked.
 * Nothing sleeps, so every assertion is on an exact millisecond, and the
 * semantics under test (30 000 ms of actual playing time, wall-clock
 * accumulation, buffering excluded, seeks not inflating time, one event per
 * session) are proven rather than approximated.
 *
 * The harness emits state the way the real controller does: a sample when
 * playback starts, stops, buffers, errors or transitions, plus position
 * updates while playing.
 */
class HistoryTrackerTest {

    // ------------------------------------------------------------------
    // 1. immediate skip
    // ------------------------------------------------------------------
    @Test
    fun `immediate skip records nothing`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.timePasses(1_000)
        h.switchTo(TRACK_B)
        assertEquals(0, h.dao.count())
    }

    // ------------------------------------------------------------------
    // 2. 29 seconds
    // ------------------------------------------------------------------
    @Test
    fun `29 seconds is below the threshold`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(29_000)
        h.switchTo(TRACK_B)
        assertEquals(0, h.dao.count())
    }

    // ------------------------------------------------------------------
    // 3. exactly 30 seconds
    // ------------------------------------------------------------------
    @Test
    fun `exactly 30 seconds qualifies`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(30_000)
        assertEquals(1, h.dao.count())
        assertEquals("a", h.dao.newestFirst().single().trackId)
    }

    @Test
    fun `a single millisecond below the threshold does not qualify`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(29_999)
        assertEquals(0, h.dao.count())
    }

    // ------------------------------------------------------------------
    // 4. 31 seconds
    // ------------------------------------------------------------------
    @Test
    fun `31 seconds qualifies and is recorded once`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(31_000)
        h.playFor(60_000)
        assertEquals(1, h.dao.count())
    }

    // ------------------------------------------------------------------
    // 5. pause / resume accumulation
    // ------------------------------------------------------------------
    @Test
    fun `accumulation continues across a pause`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(12_000)
        h.pause()
        h.timePasses(600_000) // a long pause: never counted
        h.play(TRACK_A) // resume
        h.playFor(18_000)
        assertEquals(1, h.dao.count())
    }

    @Test
    fun `a pause after qualifying does not produce a second event`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(20_000)
        h.pause()
        h.timePasses(5_000)
        h.play(TRACK_A)
        h.playFor(10_000) // total playing time is now exactly 30 s
        h.pause()
        h.play(TRACK_A)
        h.playFor(1_000)
        assertEquals(1, h.dao.count())
    }

    // ------------------------------------------------------------------
    // 6. multiple play/pause intervals
    // ------------------------------------------------------------------
    @Test
    fun `several short intervals accumulate to the threshold`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        repeat(4) {
            h.playFor(7_500)
            h.pause()
            h.timePasses(3_000)
            h.play(TRACK_A) // resume
        }
        assertEquals(1, h.dao.count())
    }

    @Test
    fun `many short intervals that never reach the threshold record nothing`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        repeat(5) {
            h.playFor(5_000)
            h.pause()
            h.timePasses(1_000)
            h.play(TRACK_A)
        }
        h.playFor(4_999) // 25 s + 4.999 s of playing, never 30 s
        h.pause()
        assertEquals(0, h.dao.count())
    }

    // ------------------------------------------------------------------
    // 7. buffering / non-playing time is excluded
    // ------------------------------------------------------------------
    @Test
    fun `buffering time is not listening time`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(10_000)
        h.buffer()
        h.timePasses(300_000) // a long stall
        h.play(TRACK_A) // playback resumed
        h.playFor(19_999)
        assertEquals(0, h.dao.count())
        h.playFor(1) // 30 s of actual playing
        assertEquals(1, h.dao.count())
    }

    @Test
    fun `an endless buffer never qualifies`() = runTest {
        val h = harness()
        h.start()
        h.buffer(TRACK_A)
        h.timePasses(600_000)
        h.switchTo(TRACK_B)
        assertEquals(0, h.dao.count())
    }

    @Test
    fun `a track that never leaves the buffer records nothing`() = runTest {
        val h = harness()
        h.start()
        h.buffer(TRACK_A)
        h.timePasses(120_000)
        h.fail(TRACK_A)
        h.timePasses(120_000)
        h.stop()
        assertEquals(0, h.dao.count())
    }

    // ------------------------------------------------------------------
    // 8. seeking does not inflate listening time
    // ------------------------------------------------------------------
    @Test
    fun `a forward seek does not create listening time`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(10_000)
        h.seekTo(310_000) // jump five minutes into the track, no time passes
        h.playFor(5_000) // five real seconds
        assertEquals(0, h.dao.count())
        h.switchTo(TRACK_B)
        assertEquals(0, h.dao.count())
    }

    @Test
    fun `a backward seek does not erase listening time`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(20_000)
        h.seekTo(0)
        h.playFor(10_000)
        assertEquals(1, h.dao.count())
    }

    // ------------------------------------------------------------------
    // 9. track change flushes the previous interval
    // ------------------------------------------------------------------
    @Test
    fun `a track change flushes the running interval of the previous track`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(10_000)
        h.pause() // 10 s banked
        h.play(TRACK_A)
        h.playFor(19_999) // 29 999 s of banked + running time, still short
        assertEquals(0, h.dao.count())
        // Wall-clock time passes and the NEXT sample is the transition itself.
        // Without flushing the running interval the session would total 19 999
        // ms and be discarded; flushing is what reaches the 30 000 ms threshold.
        h.timePasses(1)
        h.switchTo(TRACK_B)
        val rows = h.dao.newestFirst()
        assertEquals(1, rows.size)
        assertEquals("a", rows.single().trackId)
    }

    @Test
    fun `a track change while paused does not invent time`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(29_000)
        h.pause()
        h.timePasses(600_000)
        h.switchTo(TRACK_B)
        assertEquals(0, h.dao.count())
    }

    // ------------------------------------------------------------------
    // 10. track change below the threshold
    // ------------------------------------------------------------------
    @Test
    fun `neither track is recorded when both fall short`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(15_000)
        h.switchTo(TRACK_B)
        h.playFor(20_000)
        h.pause()
        h.stop()
        assertEquals(0, h.dao.count())
    }

    // ------------------------------------------------------------------
    // 11. repeat one does not duplicate
    // ------------------------------------------------------------------
    @Test
    fun `repeat one does not duplicate a qualified session`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(20_000)
        // Media3 loops the SAME item: same track, still playing, only
        // repeatLoopCount changes.
        h.repeatLoop()
        h.playFor(15_000) // 35 s -> qualifies exactly once
        repeat(4) {
            h.repeatLoop()
            h.playFor(5_000)
        }
        h.switchTo(TRACK_B)
        assertEquals(1, h.dao.count())
        assertEquals("a", h.dao.newestFirst().single().trackId)
    }

    @Test
    fun `repeat loops before qualifying still produce a single event`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(12_000)
        repeat(6) {
            h.repeatLoop()
            h.playFor(4_000) // 36 s across seven loops
        }
        assertEquals(1, h.dao.count())
    }

    // ------------------------------------------------------------------
    // 12. repeat loop time contributes
    // ------------------------------------------------------------------
    @Test
    fun `time played across a repeat loop counts toward the threshold`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(20_000)
        h.repeatLoop()
        h.playFor(10_000) // 30 s, half of it after the loop began
        assertEquals(1, h.dao.count())
    }

    // ------------------------------------------------------------------
    // 13. selected but never played
    // ------------------------------------------------------------------
    @Test
    fun `selecting a track without playing records nothing`() = runTest {
        val h = harness()
        h.start()
        h.pause(TRACK_A) // selected/loaded but never started
        h.timePasses(600_000)
        h.switchTo(TRACK_B)
        assertEquals(0, h.dao.count())
    }

    @Test
    fun `a queued but never loaded track records nothing`() = runTest {
        val h = harness()
        h.start()
        h.stop() // nothing loaded at all
        h.timePasses(120_000)
        h.stop()
        assertEquals(0, h.dao.count())
    }

    // ------------------------------------------------------------------
    // 14. failed playback
    // ------------------------------------------------------------------
    @Test
    fun `a playback error records nothing`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(5_000)
        h.fail(TRACK_A)
        h.timePasses(600_000)
        h.switchTo(TRACK_B)
        assertEquals(0, h.dao.count())
    }

    @Test
    fun `a track that only ever fails to start records nothing`() = runTest {
        val h = harness()
        h.start()
        h.buffer(TRACK_A)
        h.timePasses(30_000)
        h.fail(TRACK_A)
        h.timePasses(30_000)
        h.switchTo(TRACK_B)
        h.play(TRACK_B)
        h.playFor(5_000)
        h.pause()
        assertEquals(0, h.dao.count())
    }

    // ------------------------------------------------------------------
    // 15. a second session of the same track
    // ------------------------------------------------------------------
    @Test
    fun `replaying a track in a later session is a separate event`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(30_000)
        h.switchTo(TRACK_B)
        h.playFor(30_000)
        h.switchTo(TRACK_A) // back to A: a NEW session
        h.playFor(30_000)
        val rows = h.dao.newestFirst()
        assertEquals(3, rows.size)
        // Newest first: the replay of A, then B, then the first A session.
        assertEquals(listOf("a", "b", "a"), rows.map { it.trackId })
    }

    @Test
    fun `a new session is not credited with the previous session time`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(30_000) // logged
        h.switchTo(TRACK_B)
        h.playFor(10_000) // B: too short
        h.pause()
        h.switchTo(TRACK_A)
        h.playFor(1) // A's new session starts from zero
        assertEquals(1, h.dao.count())
    }

    // ------------------------------------------------------------------
    // 16. retention
    // ------------------------------------------------------------------
    @Test
    fun `retention keeps the newest 1000 records`() = runTest {
        val h = harness()
        h.start()
        // Pre-fill the log to the ceiling with distinct events.
        repeat(HistoryRepository.RETENTION_LIMIT) { index ->
            h.dao.insert(
                HistoryEntity(
                    providerId = "local",
                    trackId = "old-$index",
                    title = "Old $index",
                    artist = "Artist",
                    playedAt = 1_000L + index,
                ),
            )
        }
        assertEquals(HistoryRepository.RETENTION_LIMIT, h.dao.count())

        h.play(TRACK_A)
        h.playFor(30_000)

        assertEquals(HistoryRepository.RETENTION_LIMIT, h.dao.count())
        val newest = h.dao.newestFirst()
        assertEquals("a", newest.first().trackId)
        // The oldest pre-existing event is gone; the newest pre-fill event is not.
        assertEquals("old-999", newest[1].trackId)
    }

    @Test
    fun `retention runs off the calling path with the production limit`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(30_000)
        assertTrue(h.dao.trimCalls.isNotEmpty())
        assertEquals(HistoryRepository.RETENTION_LIMIT, h.dao.trimCalls.last())
    }

    // ------------------------------------------------------------------
    // Event content and timing
    // ------------------------------------------------------------------
    @Test
    fun `a recorded event snapshots the track metadata and stamps wall clock`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_WITH_METADATA)
        h.playFor(30_000)
        val row = h.dao.newestFirst().single()
        assertEquals("itunes", row.providerId)
        assertEquals("rich", row.trackId)
        assertEquals("Around the World", row.title)
        assertEquals("Daft Punk", row.artist)
        assertEquals("Discovery", row.album)
        assertEquals("https://example.test/art.jpg", row.artworkUrl)
        assertEquals(429_000L, row.durationMs)
        // The WALL clock is stored, never the monotonic accounting clock.
        assertEquals(h.wall.now, row.playedAt)
    }

    @Test
    fun `the event is written as soon as the threshold is crossed`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(29_999)
        assertEquals(0, h.dao.count())
        // Crossing the threshold logs immediately — the user does not have to
        // pause, skip or stop for the event to exist.
        h.playFor(1)
        assertEquals(1, h.dao.count())
    }

    @Test
    fun `unloading media closes the session`() = runTest {
        val h = harness()
        h.start()
        h.play(TRACK_A)
        h.playFor(30_000)
        assertEquals(1, h.dao.count())
        h.switchTo(TRACK_B)
        h.stop() // the media item is dropped
        assertEquals(1, h.dao.count())
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private fun TestScope.harness(): Harness {
        val elapsed = FakeElapsedRealtime()
        val wall = FakeWallClock()
        val dao = FakeHistoryDao()
        // The tracker collects state forever, so it must NOT be a child of the
        // test job (runTest would wait for it) and must NOT live on
        // backgroundScope (whose tasks advanceUntilIdle does not drain). An
        // explicit scope on the test's scheduler gives deterministic ordering
        // and is cancelled in [tearDown].
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        ownedScopes += scope
        val repository = HistoryRepository(dao, scope)
        val controller = MutableFakePlaybackController()
        return Harness(
            scheduler = testScheduler,
            elapsed = elapsed,
            wall = wall,
            dao = dao,
            controller = controller,
            tracker = HistoryTracker(
                repository = repository,
                scope = scope,
                elapsedRealtime = elapsed,
                wallClock = wall,
            ),
        )
    }

    /** Scopes created by [harness]; cancelled after each test method. */
    private val ownedScopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        ownedScopes.forEach { it.cancel() }
    }

    /**
     * A tiny player script. Every method emits ONE state sample, exactly like
     * the real controller does on a playback transition, and advances the
     * clocks so the sample carries a meaningful timestamp. While playing, the
     * position advances so consecutive samples are distinct — the real
     * controller polls position the same way.
     */
    private class Harness(
        private val scheduler: TestCoroutineScheduler,
        val elapsed: FakeElapsedRealtime,
        val wall: FakeWallClock,
        val dao: FakeHistoryDao,
        private val controller: MutableFakePlaybackController,
        private val tracker: HistoryTracker,
    ) {
        private var track: Track? = null
        private var positionMs = 0L
        private var loopCount = 0L
        private var isPlaying = false

        fun start() {
            tracker.start(controller)
            scheduler.advanceUntilIdle()
        }

        /** Start (or resume) [track] at the current instant. */
        fun play(track: Track) {
            if (this.track != track) {
                this.track = track
                positionMs = 0L
            }
            isPlaying = true
            emit(playingState(track, positionMs, loopCount))
        }

        /** [track] is loaded and ready but not playing. */
        fun pause(track: Track = requireNotNull(this.track)) {
            this.track = track
            isPlaying = false
            emit(pausedState(track, positionMs))
        }

        /**
         * Let [ms] of ACTUAL playback elapse, sampling as a playing player would.
         *
         * Must be preceded by a playing sample ([play], [switchTo] or another
         * [playFor]): a real player emits `isPlaying = true` at the instant
         * audio resumes, and the tracker starts its stopwatch there. Calling
         * this straight after a pause would silently credit nothing, so it is
         * rejected instead of producing a subtly wrong test.
         */
        fun playFor(ms: Long) {
            require(ms >= 0) { "cannot play backwards: $ms" }
            check(isPlaying) { "playFor() needs a playing sample first — call play(track) to resume" }
            val current = requireNotNull(track)
            advanceClocks(ms)
            positionMs += ms
            emit(playingState(current, positionMs, loopCount))
        }

        /** Media3 started buffering / rebuffering: no audio, no listening time. */
        fun buffer(track: Track? = this.track) {
            this.track = track
            isPlaying = false
            emit(bufferingState(track))
        }

        /** The player reported an error. */
        fun fail(track: Track? = this.track) {
            this.track = track
            isPlaying = false
            emit(errorState(track))
        }

        /** stop(): no media item is loaded any more. */
        fun stop() {
            track = null
            positionMs = 0L
            loopCount = 0L
            isPlaying = false
            emit(stoppedState())
        }

        /**
         * Wall-clock time passes with NO state emission — modelling the gap
         * between the player's last sample and the next transition. The tracker
         * must still credit that time at the next sample it receives.
         */
        fun timePasses(ms: Long) {
            advanceClocks(ms)
        }

        /** The player transitioned to [next] and is playing it. */
        fun switchTo(next: Track) {
            track = next
            positionMs = 0L
            isPlaying = true
            emit(playingState(next, positionMs, loopCount))
        }

        /** M16: Media3 looped the current item (REPEAT transition). */
        fun repeatLoop() {
            check(isPlaying) { "a repeat transition only happens while playing" }
            loopCount += 1
            emit(playingState(requireNotNull(track), positionMs, loopCount))
        }

        /** A seek: the position jumps, no wall-clock time passes. */
        fun seekTo(targetMs: Long) {
            positionMs = targetMs
        }

        private fun advanceClocks(ms: Long) {
            elapsed.advance(ms)
            wall.advance(ms)
        }

        private fun emit(state: com.naudio.core.player.PlayerState) {
            controller.emit(state)
            scheduler.advanceUntilIdle()
        }
    }

    private companion object {
        val TRACK_A = Track("a", "local", "Track A", "Artist A", durationMs = 200_000L)
        val TRACK_B = Track("b", "local", "Track B", "Artist B", durationMs = 200_000L)
        val TRACK_WITH_METADATA = Track(
            id = "rich",
            providerId = "itunes",
            title = "Around the World",
            artist = "Daft Punk",
            album = "Discovery",
            artworkUrl = "https://example.test/art.jpg",
            durationMs = 429_000L,
        )
    }
}
