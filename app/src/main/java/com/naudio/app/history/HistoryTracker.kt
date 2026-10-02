package com.naudio.app.history

import com.naudio.core.model.Track
import com.naudio.core.player.PlaybackController
import com.naudio.core.player.PlayerState
import com.naudio.data.repository.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A completed listening session that qualified for the history log.
 *
 * [track] is the loaded [Track] itself (not a copy): the repository snapshots
 * its metadata at insert time, which is what the log is for. [playedAt] is
 * wall-clock epoch millis — it orders events and survives a reboot, unlike the
 * monotonic clock used to measure how long the track was heard.
 */
data class HistoryEvent(val track: Track, val playedAt: Long)

/**
 * Pure listening-session accounting for ONE track (M17).
 *
 * No Android, no coroutines, no repository, no player: it is fed
 * [PlayerState] snapshots plus externally sampled timestamps and reports the
 * moment a session qualifies. Every rule the milestone asks for lives here as
 * arithmetic on those samples, which is why all sixteen scenarios are unit
 * tested without a device.
 *
 * The rules:
 *
 *  - Time accumulates ONLY while [PlayerState.isPlaying] is true. Media3
 *    already reports `isPlaying == false` while buffering, while suppressed,
 *    after ENDED and in ERROR, so buffering/rebuffering is excluded without
 *    inventing a second buffering state here.
 *  - It measures ELAPSED wall-clock playing time, never `positionMs` deltas.
 *    Seeking therefore cannot create fake listening time: a seek moves
 *    `positionMs` and nothing else, while the stopwatch keeps running, so
 *    "play 10 s, seek 10 s → 5 min, play 5 s" totals 15 s.
 *  - Accumulation CONTINUES across pauses and resumes: a pause closes the
 *    current interval and a resume opens a new one, and both count.
 *  - A track change ends the session: the running interval is flushed first
 *    (so the last stretch is never lost), the finished session is evaluated,
 *    and the new track starts from zero — including when the user returns to a
 *    track they already logged, because a new session is a new event.
 *  - M16 repeat loops are invisible here. A repeat-one loop re-emits state for
 *    the SAME track, so no session boundary is crossed and the accumulator is
 *    not reset; and because a session is logged at most once
 *    ([hasLogged]), looping never duplicates a history row. `repeatLoopCount`
 *    is deliberately NOT read — history does not need the coordinator's
 *    advance signal, and reading it would couple the two systems.
 */
internal class ListeningSession(private val thresholdMs: Long) {

    /** The track whose playing time is currently being accumulated. */
    var track: Track? = null
        private set

    /** Closed playing intervals, in ms, for the current track. */
    var accumulatedMs: Long = 0L
        private set

    /** Whether this track already produced an event (one event per session). */
    var hasLogged: Boolean = false
        private set

    /** Start of the currently running playing interval, or null when stopped. */
    private var playingSinceMs: Long? = null

    /**
     * Feed one state sample and return the event the sample completed, if any.
     * At most one event per sample: a track change closes the previous session
     * and the new track has, by definition, zero accumulated time.
     *
     * @param elapsedMs monotonic sample (`SystemClock.elapsedRealtime()`).
     * @param playedAtMs wall-clock sample used to stamp a returned event.
     */
    fun onState(state: PlayerState, elapsedMs: Long, playedAtMs: Long): HistoryEvent? {
        val incoming = state.track
        // A different track (or media unloaded) closes the current session.
        val completed = if (incoming != track) endSession(elapsedMs, playedAtMs) else null
        track = incoming
        setPlaying(state.isPlaying, elapsedMs)
        if (completed != null) return completed
        return qualifyIfThresholdReached(elapsedMs, playedAtMs)
    }

    /**
     * Close the current session: flush the running interval, emit an event if
     * the session qualified and had not already been logged, then reset for
     * the next track. Returns null when there is no session to close.
     */
    private fun endSession(elapsedMs: Long, playedAtMs: Long): HistoryEvent? {
        val sessionTrack = track ?: return null
        val totalMs = totalPlayingMs(elapsedMs)
        stopStopwatch(elapsedMs)
        accumulatedMs = 0L
        val alreadyLogged = hasLogged
        // A new track is a new session: it must qualify on its own merit.
        hasLogged = false
        return if (!alreadyLogged && totalMs >= thresholdMs) {
            HistoryEvent(sessionTrack, playedAtMs)
        } else {
            null
        }
    }

    /**
     * Log the current track the moment it crosses the threshold, so a session
     * that keeps playing after 30 s is recorded immediately rather than only
     * when it ends. The [hasLogged] guard is what stops repeat-one (and any
     * long session) from appending more than one row.
     */
    private fun qualifyIfThresholdReached(elapsedMs: Long, playedAtMs: Long): HistoryEvent? {
        val current = track ?: return null
        if (hasLogged || totalPlayingMs(elapsedMs) < thresholdMs) return null
        hasLogged = true
        return HistoryEvent(current, playedAtMs)
    }

    /** Open a playing interval on resume; close it on pause/buffer/stop. */
    private fun setPlaying(isPlaying: Boolean, elapsedMs: Long) {
        if (isPlaying) {
            if (playingSinceMs == null) playingSinceMs = elapsedMs
        } else {
            stopStopwatch(elapsedMs)
        }
    }

    private fun stopStopwatch(elapsedMs: Long) {
        val since = playingSinceMs ?: return
        playingSinceMs = null
        // Defensive: a monotonic source should never go backwards, but a
        // negative interval must never erode real accumulated time.
        accumulatedMs += (elapsedMs - since).coerceAtLeast(0L)
    }

    /** Closed intervals plus the still-running one, as of this sample. */
    private fun totalPlayingMs(elapsedMs: Long): Long {
        val running = playingSinceMs?.let { (elapsedMs - it).coerceAtLeast(0L) } ?: 0L
        return accumulatedMs + running
    }
}

/**
 * Records playback history (M17).
 *
 * Deliberately SEPARATE from [com.naudio.app.playback.PlaybackCoordinator]: the
 * coordinator owns the queue, transitions and resolution, this owns
 * listening-session accounting. The tracker only READS the one authoritative
 * [PlaybackController.state] stream that every surface already observes — it
 * creates no player, controls no playback, mutates no queue, changes no Media3
 * configuration, holds no second copy of the player state and never talks to
 * ExoPlayer.
 *
 * The pipeline is exactly
 * `PlaybackController.state → HistoryTracker → HistoryRepository → Room`, with
 * the UI and Android Auto reading the same repository read-only.
 *
 * Lifetime: one instance per application, created by [com.naudio.app.di.AppContainer]
 * and started against the shared controller. [start] returns a child [Job] of
 * the injected [scope], so cancelling the application scope cancels the
 * tracker; it holds no Activity reference and survives screen navigation.
 */
class HistoryTracker(
    private val repository: HistoryRepository,
    private val scope: CoroutineScope,
    private val elapsedRealtime: ElapsedRealtimeSource = SystemElapsedRealtimeSource,
    private val wallClock: WallClockSource = SystemWallClockSource,
    private val thresholdMs: Long = LISTENING_THRESHOLD_MS,
) {

    private val session = ListeningSession(thresholdMs)

    /**
     * Start accumulating. The returned [Job] runs for as long as [scope] lives;
     * callers own the scope, not this job.
     */
    fun start(playbackController: PlaybackController): Job = scope.launch {
        playbackController.state.collect { state ->
            // Both clocks are sampled together so a recorded event's stamp and
            // its duration come from the same instant.
            val elapsedMs = elapsedRealtime.elapsedRealtimeMs()
            val playedAtMs = wallClock.nowMs()
            val event = session.onState(state, elapsedMs, playedAtMs) ?: return@collect
            // The write is a child coroutine: the state-collection loop never
            // waits on Room, so history can never delay a state emission.
            launch { repository.record(event.track, event.playedAt) }
        }
    }

    companion object {
        /**
         * M17 threshold: 30 000 ms of ACTUAL playing time, accumulated across
         * pauses, buffers and seeks, per listening session.
         */
        const val LISTENING_THRESHOLD_MS = 30_000L
    }
}
