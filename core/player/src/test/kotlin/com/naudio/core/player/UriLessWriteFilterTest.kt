package com.naudio.core.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M18 regression tests for the guard that wraps the one ExoPlayer.
 *
 * The regression this milestone fixes was a *global* [Player.getAvailableCommands]
 * override on this filter that removed COMMAND_SET_MEDIA_ITEM (31) and
 * COMMAND_CHANGE_MEDIA_ITEMS (20). Media3 intersects every controller's
 * commands with the player's, so the app's own MediaController was refused
 * before its request ever reached the session ("Controller isn't allowed to
 * call command= 31"), which is why tapping a track produced no playback at all.
 *
 * These tests pin both halves of the fix: the commands stay available (mobile
 * playback is possible again), and the per-write guards still stop an
 * unplayable item from reaching ExoPlayer (the Auto safety purpose is kept).
 */
@RunWith(RobolectricTestRunner::class)
class UriLessWriteFilterTest {

    private val player = RecordingPlayer()
    private val filter = UriLessWriteFilter(player)

    // ------------------------------------------------------------------
    // Regression 1: the global command mask is gone
    // ------------------------------------------------------------------

    @Test
    fun `set media item and change media items commands stay available`() {
        val commands = filter.availableCommands

        // M18 regression: these two were masked out here, which is what blocked
        // MediaControllerPlaybackController.load()'s setMediaItem().
        assertTrue(
            "COMMAND_SET_MEDIA_ITEM must survive the wrapper",
            commands.contains(Player.COMMAND_SET_MEDIA_ITEM),
        )
        assertTrue(
            "COMMAND_CHANGE_MEDIA_ITEMS must survive the wrapper",
            commands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS),
        )
    }

    @Test
    fun `the wrapper advertises exactly the wrapped player's commands`() {
        assertEquals(player.availableCommands, filter.availableCommands)
    }

    // ------------------------------------------------------------------
    // The mobile path: URI-bearing writes must reach ExoPlayer
    // ------------------------------------------------------------------

    @Test
    fun `a uri bearing media item reaches the player`() {
        val item = playableItem("file:///sdcard/Music/NaudioShortLoop.wav")

        filter.setMediaItem(item)

        assertEquals(listOf(item), player.setCalls.single().items)
    }

    @Test
    fun `a uri bearing item list with a start index reaches the player`() {
        val first = playableItem("file:///sdcard/Music/NaudioLongTone.wav")
        val second = playableItem("file:///sdcard/Music/NaudioShortLoop.wav")

        filter.setMediaItems(mutableListOf(first, second), /* startIndex = */ 1, /* startPositionMs = */ 0L)

        val call = player.setCalls.single()
        assertEquals(listOf(first, second), call.items)
        assertEquals(1, call.startIndex)
    }

    @Test
    fun `a uri bearing add media item reaches the player`() {
        val item = playableItem("content://media/external/audio/media/39")

        filter.addMediaItem(item)

        assertEquals(listOf(item), player.addCalls.single().items)
    }

    // ------------------------------------------------------------------
    // The Auto safety invariant: unplayable writes never reach ExoPlayer
    // ------------------------------------------------------------------

    @Test
    fun `a uri less media item never reaches the player`() {
        filter.setMediaItem(uriLessItem("track:abc"))

        assertTrue(player.setCalls.isEmpty())
    }

    @Test
    fun `a uri less media item list never reaches the player`() {
        filter.setMediaItems(
            mutableListOf(uriLessItem("track:abc"), uriLessItem("track:def")),
            /* startIndex = */ 0,
            /* startPositionMs = */ 0L,
        )

        assertTrue(player.setCalls.isEmpty())
    }

    @Test
    fun `a list where only one item lacks a uri never reaches the player`() {
        filter.setMediaItems(
            mutableListOf(
                playableItem("file:///sdcard/Music/NaudioShortLoop.wav"),
                uriLessItem("track:abc"),
            ),
            /* startIndex = */ 0,
            /* startPositionMs = */ 0L,
        )

        assertTrue(player.setCalls.isEmpty())
    }

    @Test
    fun `an empty media item list never reaches the player`() {
        // An unresolved Auto request answers with an empty list; forwarding it
        // would wipe the coordinator-owned timeline.
        filter.setMediaItems(mutableListOf(), /* startIndex = */ 0, /* startPositionMs = */ 0L)
        filter.setMediaItems(mutableListOf(), /* resetPosition = */ true)

        assertTrue(player.setCalls.isEmpty())
    }

    @Test
    fun `a uri less add media item never reaches the player`() {
        filter.addMediaItem(uriLessItem("track:abc"))
        filter.addMediaItems(mutableListOf(playableItem("file:///a.wav"), uriLessItem("track:abc")))

        assertTrue(player.addCalls.isEmpty())
    }

    @Test
    fun `queue mutation and seek remain no ops`() {
        filter.moveMediaItem(0, 1)
        filter.removeMediaItem(0)
        filter.removeMediaItems(0, 1)
        filter.clearMediaItems()
        filter.seekTo(0, 1_000L)
        filter.seekToNextMediaItem()
        filter.seekToPreviousMediaItem()

        assertTrue(player.mutationCalls.isEmpty())
        assertTrue(player.setCalls.isEmpty())
    }

    @Test
    fun `the wrapper stays transparent for what it does not guard`() {
        filter.release()

        assertTrue(player.wasReleased)
    }

    private fun playableItem(uri: String): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Tone").build())
        .build()

    private fun uriLessItem(mediaId: String): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Browsed track").build())
        .build()
}