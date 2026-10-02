package com.naudio.provider.api

import com.naudio.core.model.Lyrics
import com.naudio.core.model.Track

/**
 * Third provider capability beside [MetadataProvider]/[PlaybackProvider]:
 * supplies lyrics for tracks. Lyrics are a display concern — this interface
 * must never depend on a player, own playback, or touch the queue.
 *
 * Contract:
 *  - Returns exactly one [Lyrics] variant. Transport/server failures degrade
 *    to [Lyrics.Unavailable] (never [Lyrics.NotFound], which is reserved for
 *    a confidently-gated "no acceptable lyrics exist" result) and
 *    [CancellationException] always propagates untouched.
 *  - Matching is entirely the provider's responsibility; implementations
 *    must not accept obviously incorrect matches just because a candidate is
 *    the top search hit.
 *  - Cold and safe to call from any dispatcher. [Track.providerId] is NOT a
 *    lyrics matching signal (lyrics sources are provider-agnostic).
 *
 * Implementations live in their own provider module (e.g. provider/lrclib)
 * and are selected by the app container, keeping the rest of the app
 * dependent only on this interface.
 */
interface LyricsProvider {
    /** Stable identifier of this provider, used for attribution/logging. */
    val id: ProviderId

    /** Human-readable provider name shown as lyrics attribution in the UI. */
    val displayName: String

    /**
     * Resolve lyrics for [track], or a semantic no-lyrics variant
     * ([Lyrics.NotFound] / [Lyrics.Instrumental]). Never throws except
     * [CancellationException].
     */
    suspend fun getLyrics(track: Track): Lyrics
}
