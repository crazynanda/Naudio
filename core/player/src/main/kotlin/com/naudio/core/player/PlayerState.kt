package com.naudio.core.player

import com.naudio.core.model.Track

/** Coarse playback status, deliberately decoupled from Media3 constants. */
enum class PlaybackStatus {
    /** No media prepared; nothing loaded yet (or stopped). */
    IDLE,

    /** Loading/buffering media. */
    BUFFERING,

    /** Ready and either playing or paused. */
    READY,

    /** Media finished playing to the end. */
    ENDED,

    /** A playback error occurred; [PlayerState.errorMessage] carries detail. */
    ERROR,
}

/**
 * Snapshot of the player, exposed to the UI. Contains no Media3 types so the
 * UI never depends on ExoPlayer and the playback implementation stays
 * replaceable.
 */
data class PlayerState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val track: Track? = null,
    val errorMessage: String? = null,
) {
    val isSeekable: Boolean
        get() = durationMs > 0L
}

/**
 * Pure mapping helpers from Media3 player constants to [PlayerState] fields.
 * Unit-tested without an Android device.
 */
object PlayerStateMapper {

    /** Media3 playback state constant → [PlaybackStatus]. */
    fun statusOf(media3PlaybackState: Int): PlaybackStatus = when (media3PlaybackState) {
        2 -> PlaybackStatus.BUFFERING // Player.STATE_BUFFERING
        3 -> PlaybackStatus.READY // Player.STATE_READY
        4 -> PlaybackStatus.ENDED // Player.STATE_ENDED
        1 -> PlaybackStatus.IDLE // Player.STATE_IDLE
        else -> PlaybackStatus.IDLE
    }

    /** Maps a thrown error to a [PlayerState] in [PlaybackStatus.ERROR]. */
    fun errorOf(message: String): PlayerState =
        PlayerState(status = PlaybackStatus.ERROR, errorMessage = message)
}
