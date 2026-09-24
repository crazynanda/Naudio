package com.naudio.core.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Media3-backed [PlaybackController]: the app talks to this, this talks to the
 * [NaudioPlaybackService] through a [MediaController]. ExoPlayer itself lives
 * only inside the service — the UI never touches it.
 */
class MediaControllerPlaybackController(context: Context) : PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val ticker = PositionTicker(POSITION_POLL_INTERVAL_MS)

    private var mediaController: MediaController? = null

    /** Commands issued before the session connection completes. */
    private val pendingCommands = ArrayDeque<(MediaController) -> Unit>()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = refreshSnapshot()
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshSnapshot()

        override fun onPlayerError(error: PlaybackException) {
            _state.update {
                it.copy(
                    status = PlaybackStatus.ERROR,
                    isPlaying = false,
                    errorMessage = error.errorCodeName,
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _state.update {
                it.copy(
                    track = mediaItem?.localConfiguration?.tag as? Track ?: it.track,
                    errorMessage = null,
                )
            }
        }
    }

    init {
        scope.launch {
            val controller = buildMediaController(context)
            mediaController = controller
            controller.addListener(playerListener)
            flushPendingCommands()
            refreshSnapshot()
        }
        ticker.ticks(_state.asStateFlow().map { it.isPlaying })
            .onEach { pollPosition() }
            .launchIn(scope)
    }

    override fun load(track: Track, source: AudioSource) {
        _state.update {
            it.copy(
                status = PlaybackStatus.BUFFERING,
                track = track,
                errorMessage = null,
                positionMs = 0L,
            )
        }
        whenConnected { controller ->
            controller.setMediaItem(buildMediaItem(track, source))
            controller.prepare()
            controller.play()
        }
    }

    override fun play() = whenConnected { it.play() }

    override fun pause() = whenConnected { it.pause() }

    override fun stop() {
        whenConnected { controller ->
            controller.stop()
            controller.clearMediaItems()
        }
        _state.update { PlayerState() }
    }

    override fun seekTo(positionMs: Long) = whenConnected { controller ->
        controller.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun release() {
        pendingCommands.clear()
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        mediaController = null
        scope.cancel()
    }

    private fun whenConnected(command: (MediaController) -> Unit) {
        val controller = mediaController
        if (controller != null) {
            command(controller)
        } else {
            pendingCommands.add(command)
        }
    }

    private fun flushPendingCommands() {
        val controller = mediaController ?: return
        while (pendingCommands.isNotEmpty()) {
            pendingCommands.removeFirst().invoke(controller)
        }
    }

    private fun pollPosition() {
        val controller = mediaController ?: return
        val position = controller.currentPosition.coerceAtLeast(0L)
        _state.update { current ->
            if (current.positionMs != position) current.copy(positionMs = position) else current
        }
    }

    private fun refreshSnapshot() {
        val controller = mediaController ?: return
        _state.update { current ->
            current.copy(
                status = PlayerStateMapper.statusOf(controller.playbackState),
                isPlaying = controller.isPlaying,
                durationMs = if (controller.playbackState == Player.STATE_READY) {
                    controller.duration.coerceAtLeast(0L)
                } else {
                    current.durationMs
                },
            )
        }
    }

    private fun buildMediaItem(track: Track, source: AudioSource): MediaItem {
        val uri = when (source) {
            is AudioSource.Local -> Uri.parse(source.uri)
            is AudioSource.Remote -> Uri.parse(source.url)
        }
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .build(),
            )
            .setTag(track)
            .build()
    }

    private suspend fun buildMediaController(context: Context): MediaController {
        val token = SessionToken(context, ComponentName(context, NaudioPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val deferred = CompletableDeferred<MediaController>()
        future.addListener(
            {
                try {
                    deferred.complete(future.get())
                } catch (t: Throwable) {
                    deferred.completeExceptionally(t)
                }
            },
            MoreExecutors.directExecutor(),
        )
        return deferred.await()
    }

    private companion object {
        const val POSITION_POLL_INTERVAL_MS = 500L
    }
}
