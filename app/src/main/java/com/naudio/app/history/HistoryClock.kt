package com.naudio.app.history

/**
 * The two clock seams the M17 history accounting needs, as interfaces so the
 * tracker stays a pure state machine that unit tests drive by hand — no
 * `Thread.sleep`, no real time, no Android.
 *
 * Both exist because listening DURATION and event ORDERING need different time
 * bases, and conflating them is a real bug:
 *
 *  - [ElapsedRealtimeSource] is MONOTONIC, and is the only value used to
 *    accumulate listening time. Production is `SystemClock.elapsedRealtime()`.
 *    A monotonic clock cannot jump backwards when the wall clock is corrected,
 *    is NTP-adjusted or the user changes the date, so a session can never lose
 *    or gain time. It is also not a usable ORDERING key: it is only meaningful
 *    since boot.
 *  - [WallClockSource] is the EPOCH time used to stamp and order history
 *    events. Production is `System.currentTimeMillis()`, which stays correct
 *    across a reboot — so "Recently Played" does not reshuffle into a bogus
 *    order the next morning.
 */
fun interface ElapsedRealtimeSource {
    /** Monotonic milliseconds, e.g. `SystemClock.elapsedRealtime()`. */
    fun elapsedRealtimeMs(): Long
}

/** Wall-clock epoch milliseconds, e.g. `System.currentTimeMillis()`. */
fun interface WallClockSource {
    fun nowMs(): Long
}
