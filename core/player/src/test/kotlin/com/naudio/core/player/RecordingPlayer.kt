package com.naudio.core.player

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A genuine Media3 [Player] (Media3's own [SimpleBasePlayer]) that records the
 * writes it receives instead of playing them.
 *
 * Deliberately NOT a hand-rolled fake of the [Player] interface: the M18
 * regression only reproduces faithfully against a real Media3 player, because
 * Media3 intersects each controller's available commands with the player's and
 * routes every set/add-media-items call through
 * [androidx.media3.session.MediaSession.Callback] before the write lands.
 *
 * [RecordingPlayer.setCalls] being empty therefore proves the write never
 * reached the player — the exact invariant the Auto path depends on.
 */
internal class RecordingPlayer : SimpleBasePlayer(Looper.getMainLooper()) {

    data class SetCall(val items: List<MediaItem>, val startIndex: Int, val startPositionMs: Long)

    data class AddCall(val index: Int, val items: List<MediaItem>)

    val setCalls = mutableListOf<SetCall>()
    val addCalls = mutableListOf<AddCall>()

    /** Every queue mutation / seek that actually reached the player. */
    val mutationCalls = mutableListOf<String>()

    var wasReleased = false

    override fun getState(): SimpleBasePlayer.State =
        SimpleBasePlayer.State.Builder()
            // Everything a normal ExoPlayer offers a connected controller.
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .build()

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        setCalls += SetCall(mediaItems.toList(), startIndex, startPositionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        addCalls += AddCall(index, mediaItems.toList())
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int,
    ): ListenableFuture<*> {
        mutationCalls += "move"
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        mutationCalls += "remove"
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        mutationCalls += "seek"
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        wasReleased = true
        return Futures.immediateVoidFuture()
    }
}