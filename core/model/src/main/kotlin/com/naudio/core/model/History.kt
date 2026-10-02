package com.naudio.core.model

/**
 * One playback-history EVENT (M17) — the record of a track that accumulated at
 * least 30 000 ms of actual playing time in a single listening session.
 *
 * History is an append-only event log, not a unique-track table: playing the
 * same track again in a later session produces another [History] row, and
 * "Recently Played" therefore shows listening events newest first. Nothing is
 * deduplicated on write or on read.
 *
 * The title/artist/album/artworkUrl/durationMs fields are an intentional
 * metadata SNAPSHOT taken when the event happened, so the Recently Played row
 * and the Android Auto node can render without any provider lookup or network
 * call. They are never written back and never refreshed.
 *
 * Deliberately absent (M17 non-goals): play counts, completion percentages,
 * analytics, scrobbling timestamps, recommendation signals.
 *
 * Pure Kotlin — no Android, no Room, no Media3. The Room counterpart is
 * `HistoryEntity` in :core:database, mapped by `HistoryMapper` in :data.
 */
data class History(
    /** Row id, assigned on insert. 0 until persisted. */
    val id: Long = 0L,
    val providerId: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    /**
     * Wall-clock epoch millis of the event (`System.currentTimeMillis()`, not
     * the monotonic clock used for duration accounting), so ordering survives
     * a device reboot.
     */
    val playedAt: Long,
) {
    /**
     * The snapshot as a [Track], so a history row can re-enter playback through
     * the EXISTING queue path (Home tap, Android Auto play) with no new
     * playback architecture.
     */
    fun toTrack(): Track = Track(
        id = trackId,
        providerId = providerId,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        durationMs = durationMs,
    )
}
