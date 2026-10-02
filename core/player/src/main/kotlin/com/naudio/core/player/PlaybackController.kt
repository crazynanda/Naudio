package com.naudio.core.player

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * The UI-facing playback API. No Media3 types: implementations adapt any
 * player backend (MediaController, in-process player, test double). The UI
 * never owns or references ExoPlayer.
 */
interface PlaybackController {

    /** Latest playback snapshot; emits on every relevant change. */
    val state: StateFlow<PlayerState>

    /**
     * Prepare [source] for [track] and start playback when ready.
     * Commands issued while disconnected are buffered and flushed on connect.
     */
    fun load(track: Track, source: AudioSource)

    /** Resume playback. */
    fun play()

    /** Pause playback. */
    fun pause()

    /** Stop playback and release the loaded media back to idle. */
    fun stop()

    /** Seek within the current media, clamped to [0, duration]. */
    fun seekTo(positionMs: Long)

    /**
     * M16: enable or disable the player's shuffle mode. The resulting state
     * is reported back through [state] by the implementation — callers must
     * not update their own state optimistically.
     */
    fun setShuffleModeEnabled(enabled: Boolean)

    /**
     * M16: set the player's repeat mode. [repeatMode] is one of Media3's
     * `Player.REPEAT_MODE_*` constants (`OFF`, `ONE`, `ALL`); the effective
     * value is reported back through [state].
     */
    fun setRepeatMode(repeatMode: Int)

    /** Disconnect and stop issuing commands; safe to call multiple times. */
    fun release()
}
