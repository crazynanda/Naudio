package com.naudio.core.model

/**
 * A playable source handle for a track, resolved by a playback provider.
 * Kept in :core:model so both the provider API and the player module can
 * reference it without coupling either to the other.
 */
sealed interface AudioSource {
    /** A source resolvable on-device (app asset, file, content URI). */
    data class Local(val uri: String) : AudioSource

    /** A remote stream URL. Unused this milestone; reserved for online providers. */
    data class Remote(val url: String) : AudioSource
}
