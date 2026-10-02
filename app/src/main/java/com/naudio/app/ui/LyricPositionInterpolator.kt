package com.naudio.app.ui

import com.naudio.core.model.LyricLine

/**
 * Local, visual-only position interpolator for synchronized lyrics.
 *
 * The authoritative playback position is `PlayerState.positionMs`, which is
 * refreshed by the existing [com.naudio.core.player.PositionTicker] roughly
 * every 500 ms. Rendering the active line straight from that value makes the
 * highlight visibly step twice a second. This holder adds a smooth, purely
 * visual value *between* those authoritative samples:
 *
 * ```
 * interpolated = authoritativePositionMs + (frameTimeMs - anchorFrameTimeMs)
 * ```
 *
 * Deliberate properties:
 *  - It is a plain Kotlin class with no Compose/Android imports, so the maths
 *    is unit-tested on the JVM without a frame clock.
 *  - [reanchor] is called for EVERY authoritative `positionMs` arrival, so the
 *    interpolation restarts from player truth at least twice a second. Drift
 *    can therefore never accumulate beyond one poll interval.
 *  - While paused, [positionAt] returns the authoritative position verbatim and
 *    ignores frame time entirely, so a paused player never drifts forward.
 *  - A forward or backward seek is corrected the moment the new authoritative
 *    position arrives, because the new sample becomes the new anchor.
 *  - [reset] clears all state so a previous track's interpolation can never
 *    leak into the next one.
 *
 * This is a UI convenience only. It is never a playback source of truth and
 * never feeds the player, the coordinator, or the session.
 */
internal class LyricPositionInterpolator {

    /** Latest authoritative position from `PlayerState.positionMs`. */
    var authoritativePositionMs: Long = 0L
        private set

    /** Frame time at which [authoritativePositionMs] was recorded. */
    var anchorFrameTimeMs: Long = 0L
        private set

    /** Whether the clock is running (true) or frozen (false). */
    var isInterpolating: Boolean = false
        private set

    /**
     * Record a new authoritative position and restart interpolation from it.
     *
     * @param positionMs authoritative `PlayerState.positionMs` for this sample
     * @param isPlaying whether playback is running
     * @param frameTimeMs monotonic frame time of the current frame, in the same
     *   clock used by [positionAt] (Compose's `withFrameMillis`)
     */
    fun reanchor(positionMs: Long, isPlaying: Boolean, frameTimeMs: Long) {
        authoritativePositionMs = positionMs
        anchorFrameTimeMs = frameTimeMs
        isInterpolating = isPlaying
    }

    /** Drop all interpolation state (track change). */
    fun reset() {
        authoritativePositionMs = 0L
        anchorFrameTimeMs = 0L
        isInterpolating = false
    }

    /**
     * Visual position for [frameTimeMs].
     *
     * When the clock is not running this is exactly the authoritative
     * position; otherwise the authoritative position advanced by the elapsed
     * frame time since the last anchor. Never returns a negative position.
     */
    fun positionAt(frameTimeMs: Long): Long {
        if (!isInterpolating) return authoritativePositionMs
        val elapsed = (frameTimeMs - anchorFrameTimeMs).coerceAtLeast(0L)
        return authoritativePositionMs + elapsed
    }
}

/**
 * Index of the active lyric line for [positionMs]: the last line whose
 * [LyricLine.startTimeMs] is `<=` [positionMs].
 *
 * Binary search, so the cost per frame is O(log n) regardless of lyric file
 * size. Returns `-1` when no line is active yet (position before the first
 * timestamp) or when [lines] is empty; returns the last index once playback
 * passes the final line.
 *
 * Pure function: unit-tested directly on the JVM.
 */
internal fun findActiveLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    if (positionMs < lines.first().startTimeMs) return -1

    var low = 0
    var high = lines.lastIndex
    var best = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].startTimeMs <= positionMs) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}
