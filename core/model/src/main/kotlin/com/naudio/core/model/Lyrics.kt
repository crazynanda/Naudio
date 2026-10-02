package com.naudio.core.model

/** One line of a time-synchronized lyric, anchored at its start time. */
data class LyricLine(
    /** Milliseconds from the start of the track at which this line begins. */
    val startTimeMs: Long,
    /** Display text of the line. */
    val text: String,
)

/**
 * Lyrics for a track, resolved by a lyrics provider. Deliberately decoupled
 * from any provider: implementations translate their own payloads into these
 * variants, and the UI renders each variant without knowing where it came
 * from. Semantic distinctions are load-bearing:
 *
 *  - [Synced] / [Plain]: lyrics were found; the UI knows whether to
 *    synchronize.
 *  - [NotFound]: no acceptable lyrics exist for this track. This is a real
 *    (cacheable) result, never a transport failure in disguise.
 *  - [Instrumental]: the provider explicitly reports the track as
 *    instrumental.
 *  - [Unavailable]: a transient network/server/provider failure. Never
 *    silently collapsed into [NotFound]; retrying may succeed.
 */
sealed interface Lyrics {

    /** Time-synchronized lyrics; [lines] is sorted ascending by [LyricLine.startTimeMs]. */
    data class Synced(
        val lines: List<LyricLine>,
        val providerName: String,
    ) : Lyrics

    /** Unsynced lyrics rendered as static text. */
    data class Plain(
        val text: String,
        val providerName: String,
    ) : Lyrics

    /** No acceptable lyrics were found for the track (conservatively gated). */
    data object NotFound : Lyrics

    /** The provider explicitly marks the track as instrumental. */
    data object Instrumental : Lyrics

    /** A transient failure (network/server/provider); a retry may succeed. */
    data object Unavailable : Lyrics
}
