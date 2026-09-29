package com.naudio.app.playback

/** User-visible reason the last playback request could not be served. */
enum class PlaybackError {
    /** No playback provider is registered for the track's provider — e.g. YouTube Music. */
    UNAVAILABLE,

    /** A playback provider exists but could not resolve a playable source (e.g. network failure). */
    UNRESOLVABLE,
}
