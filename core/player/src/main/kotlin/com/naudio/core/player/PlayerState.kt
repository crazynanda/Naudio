package com.naudio.core.player

import androidx.media3.common.Player
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
    /**
     * M16: whether Media3 shuffle mode is enabled. Always mirrors the actual
     * player state — it is never an optimistic UI-side guess.
     */
    val shuffleModeEnabled: Boolean = false,
    /**
     * M16: the actual Media3 repeat mode, one of [Player.REPEAT_MODE_OFF],
     * [Player.REPEAT_MODE_ONE] or [Player.REPEAT_MODE_ALL]. Media3 is the
     * single source of truth for this value so the mobile UI and Android Auto
     * can never disagree.
     */
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /**
     * M16: incremented every time Media3 loops the current item
     * (`MEDIA_ITEM_TRANSITION_REASON_REPEAT`).
     *
     * Naudio's queue lives in the app-side coordinator while Media3 holds a
     * single-item timeline, so a repeating player never reports ENDED. This
     * counter is how the coordinator learns that a repeating item finished, in
     * order to advance the queue for [Player.REPEAT_MODE_ALL]. It is an
     * internal signal, not UI state.
     */
    val repeatLoopCount: Long = 0L,
) {
    val isSeekable: Boolean
        get() = durationMs > 0L

    /** True while the player is loading/buffering media (spinner state). */
    val isBuffering: Boolean
        get() = status == PlaybackStatus.BUFFERING

    /**
     * M16: repeat is off (no looping). Defensive: any value that is not ONE or
     * ALL is reported as off, matching the UI's fallback label.
     */
    val isRepeatOff: Boolean
        get() = !isRepeatOne && !isRepeatAll

    /** M16: repeat is set to loop the current track. */
    val isRepeatOne: Boolean
        get() = repeatMode == Player.REPEAT_MODE_ONE

    /** M16: repeat is set to loop the whole queue. */
    val isRepeatAll: Boolean
        get() = repeatMode == Player.REPEAT_MODE_ALL
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

    /**
     * M16: the next repeat mode in the UI cycle
     * OFF → ALL → ONE → OFF, using Media3's own constants.
     *
     * Pure, so the cycle is unit-tested without a device. An unrecognised
     * input falls back to [Player.REPEAT_MODE_OFF] rather than throwing.
     */
    fun nextRepeatMode(current: Int): Int = when (current) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
        else -> Player.REPEAT_MODE_OFF
    }
}
