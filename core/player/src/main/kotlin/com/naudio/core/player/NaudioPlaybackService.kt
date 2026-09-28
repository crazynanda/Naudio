package com.naudio.core.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the ExoPlayer and the MediaSession. The app binds via MediaController
 * (SessionToken); Media3 promotes the service to a mediaPlayback foreground
 * service and drives the default media notification while playing. Playback
 * continues when the UI is not visible.
 */
class NaudioPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(this)
            // HTTP(S) sources (AudioSource.Remote): bounded timeouts aligned
            // with :core:network (connect 10 s / read 15 s) and cross-protocol
            // redirects for CDN preview links. DefaultDataSource dispatches
            // asset/content/file URIs itself, so local playback is unchanged.
            // (ExoPlayer.Builder has no setDataSourceFactory in this Media3
            // version; the data source factory is set via the media source.)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(
                        this,
                        DefaultHttpDataSource.Factory()
                            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
                            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
                            .setAllowCrossProtocolRedirects(true),
                    ),
                ),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val sessionActivity = PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = mediaSession
        if (session == null ||
            !session.player.playWhenReady ||
            session.player.mediaItemCount == 0
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private companion object {
        /** Matches :core:network's connect timeout. */
        const val HTTP_CONNECT_TIMEOUT_MS = 10_000

        /** Matches :core:network's request/socket timeout. */
        const val HTTP_READ_TIMEOUT_MS = 15_000
    }
}
