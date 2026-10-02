package com.naudio.app.history

import android.os.SystemClock

/**
 * Production clock sources for the history tracker — the ONLY Android types in
 * the history package, and they are the tracker's default wiring. Everything
 * else in :app/history is plain Kotlin and unit-testable, because tests inject
 * their own [ElapsedRealtimeSource]/[WallClockSource] and never touch these.
 */

/**
 * Monotonic milliseconds since boot. Unaffected by wall-clock corrections, so
 * accumulated listening time can never be inflated or lost by a clock change.
 *
 * It also survives deep sleep (unlike `uptimeMillis`), which is what we want:
 * a track playing in the background is genuinely being listened to.
 */
object SystemElapsedRealtimeSource : ElapsedRealtimeSource {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}

/** Epoch milliseconds — the ordering/stamp value stored in `history.played_at`. */
object SystemWallClockSource : WallClockSource {
    override fun nowMs(): Long = System.currentTimeMillis()
}
