package com.naudio.app.history

import com.naudio.core.database.dao.HistoryDao
import com.naudio.core.database.entity.HistoryEntity
import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.core.player.PlaybackController
import com.naudio.core.player.PlayerState
import com.naudio.core.player.PlaybackStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Deterministic fakes for the history tests. No real time, no Room, no
 * ExoPlayer: the tracker is driven purely by clock advances and PlayerState
 * samples, so every assertion is on an exact millisecond and the semantics
 * under test are proven rather than approximated.
 */

/** Manually advanced monotonic clock (the `SystemClock.elapsedRealtime()` seam). */
internal class FakeElapsedRealtime(var now: Long = 0L) : ElapsedRealtimeSource {
    override fun elapsedRealtimeMs(): Long = now

    fun advance(deltaMs: Long) {
        require(deltaMs >= 0) { "time only moves forward: $deltaMs" }
        now += deltaMs
    }
}

/** Manually advanced wall clock (the `System.currentTimeMillis()` seam). */
internal class FakeWallClock(var now: Long = 1_000_000L) : WallClockSource {
    override fun nowMs(): Long = now

    fun advance(deltaMs: Long) {
        require(deltaMs >= 0) { "time only moves forward: $deltaMs" }
        now += deltaMs
    }
}

/**
 * In-memory [HistoryDao] faithful to the Room behaviour the repository relies
 * on: an auto-incrementing id, append-only inserts (never a replace), the same
 * newest-first ordering, and the same retention trim.
 */
internal class FakeHistoryDao : HistoryDao {

    val rows = mutableListOf<HistoryEntity>()
    private val state = MutableStateFlow<List<HistoryEntity>>(emptyList())
    private var nextId = 1L

    /** Trim calls, so tests can assert retention ran off the caller's path. */
    var trimCalls = mutableListOf<Int>()

    private fun publish() {
        state.value = newestFirst()
    }

    override fun observeRecent(limit: Int): Flow<List<HistoryEntity>> =
        state.map { entries -> entries.take(limit) }

    override suspend fun insert(entry: HistoryEntity): Long {
        val id = nextId++
        rows += entry.copy(id = id)
        publish()
        return id
    }

    override suspend fun deleteBeyondNewest(keep: Int) {
        trimCalls += keep
        val survivors = newestFirst().take(keep)
        rows.clear()
        rows += survivors.sortedBy { it.id }
        publish()
    }

    override suspend fun count(): Int = rows.size

    /** The stored events, newest first — the order the UI and Auto see. */
    fun newestFirst(): List<HistoryEntity> = rows.sortedWith(
        compareByDescending<HistoryEntity> { it.playedAt }.thenByDescending { it.id },
    )
}

/**
 * PlaybackController double with a directly writable state, so a test can emit
 * any snapshot the real player could produce (ready/buffering/error, playing
 * or not, any position and repeat-loop count).
 */
internal class MutableFakePlaybackController(initial: PlayerState = PlayerState()) : PlaybackController {

    private val _state = MutableStateFlow(initial)
    override val state = _state

    fun emit(next: PlayerState) {
        _state.value = next
    }

    override fun load(track: Track, source: AudioSource) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun stop() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setShuffleModeEnabled(enabled: Boolean) = Unit
    override fun setRepeatMode(repeatMode: Int) = Unit
    override fun release() = Unit
}

// ----------------------------------------------------------------------
// State snapshots, mirroring what MediaControllerPlaybackController emits.
// ----------------------------------------------------------------------

/** Ready and genuinely playing — the only state that accumulates time. */
internal fun playingState(track: Track, positionMs: Long, repeatLoopCount: Long = 0L) = PlayerState(
    status = PlaybackStatus.READY,
    isPlaying = true,
    positionMs = positionMs,
    track = track,
    repeatLoopCount = repeatLoopCount,
)

/** Ready but paused: the stopwatch is closed, accumulated time is kept. */
internal fun pausedState(track: Track, positionMs: Long) = PlayerState(
    status = PlaybackStatus.READY,
    isPlaying = false,
    positionMs = positionMs,
    track = track,
)

/** Media3 reports isPlaying == false while buffering — see the tracker docs. */
internal fun bufferingState(track: Track?) = PlayerState(
    status = PlaybackStatus.BUFFERING,
    isPlaying = false,
    track = track,
)

internal fun errorState(track: Track?, message: String = "ERROR_CODE_IO") = PlayerState(
    status = PlaybackStatus.ERROR,
    isPlaying = false,
    track = track,
    errorMessage = message,
)

/** stop(): the media item is dropped, so no track is loaded. */
internal fun stoppedState() = PlayerState()
