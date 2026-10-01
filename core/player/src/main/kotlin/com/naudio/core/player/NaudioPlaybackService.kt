package com.naudio.core.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.google.common.collect.ImmutableList
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Implemented by the Application so the service (which lives in :core:player,
 * below the app layer) can obtain the app-owned bridge/browse-tree at
 * onCreate. Application.onCreate always completes before any service starts,
 * so the dependencies are ready whenever the service is created.
 */
interface PlaybackDependenciesProvider {
    val autoPlaybackBridge: AutoPlaybackBridge
    val autoBrowseTree: AutoBrowseTreeProvider
}

/**
 * The single playback engine of the app (M14): owns the one ExoPlayer and the
 * one [MediaLibrarySession]. The mobile UI connects through a MediaController
 * (unchanged since M10); Android Auto / Automotive browsers connect as
 * MediaBrowser clients of the same session.
 *
 * Android Auto integration is wired without touching PlaybackCoordinator,
 * the queue or any provider:
 *
 *  - browsing:  [NaudioLibraryCallback] serves the browse tree from the
 *    app-supplied [AutoBrowseTreeProvider] (favorites / playlists / tracks).
 *  - playback:  Auto requests items WITHOUT a URI (onSetMediaItems /
 *    onAddMediaItems). The callback resolves the id via the app-supplied
 *    [AutoPlaybackBridge] — which delegates through the existing
 *    PlaybackCoordinator's resolution path — and returns playable MediaItems
 *    to Media3. There is exactly one player, one queue and one coordinator;
 *    nothing Android-Auto-specific is created.
 *  - routing:   the session's [Player] is wrapped in [UriLessWriteFilter] so
 *    external add/set-item requests (which always arrive URI-less here) are
 *    answered by the callback, while the app's own controller always writes
 *    fully-resolved items (with URI) straight to the player — mobile playback
 *    keeps its exact M9–M13 pathway.
 *  - commands:  next/previous are intercepted and delegated to the shared
 *    coordinator so queue movement stays synchronized for every surface.
 */
@UnstableApi
class NaudioPlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null

    /** Session-lifetime scope for async library callbacks. */
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Single-threaded executor for library-result callbacks; shut down in onDestroy. */
    private val libraryExecutor = Executors.newSingleThreadExecutor()

    // Resolved from the Application in onCreate (see PlaybackDependenciesProvider).
    private var bridge: AutoPlaybackBridge? = null
    private var browseTree: AutoBrowseTreeProvider? = null

    /** Lazy: the playback delegation logic over the resolved dependencies. */
    private var requestResolver: AutoPlaybackRequestResolver? = null

    private val libraryCallback = NaudioLibraryCallback()

    override fun onCreate() {
        super.onCreate()
        val dependencies = application as? PlaybackDependenciesProvider
        check(dependencies != null) {
            "NaudioPlaybackService requires the Application to implement PlaybackDependenciesProvider"
        }
        bridge = dependencies.autoPlaybackBridge
        browseTree = dependencies.autoBrowseTree
        requestResolver = AutoPlaybackRequestResolver(
            browseTree = browseTree!!,
            bridge = bridge!!,
        )
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(this)
            // HTTP(S) sources (AudioSource.Remote): bounded timeouts aligned
            // with :core:network (connect 10 s / read 15 s) and cross-protocol
            // redirects for CDN preview links. DefaultDataSource dispatches
            // asset/content/file URIs itself, so local playback is unchanged.
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
        mediaLibrarySession = MediaLibrarySession.Builder(this, UriLessWriteFilter(player), libraryCallback)
            .setSessionActivity(sessionActivity)
            .build()
        // Only URI-carrying writes reach the wrapped player. The app's own
        // controller always writes resolved items (with URI); every external
        // request (Android Auto, Assistant, Bluetooth) arrives URI-less and is
        // routed through the callback → bridge → coordinator instead.
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = mediaLibrarySession
        if (session == null ||
            !session.player.playWhenReady ||
            session.player.mediaItemCount == 0
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        libraryExecutor.shutdown()
        sessionScope.cancel()
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }

    /**
     * Blocks external set/add-item writes that would hand an unplayable
     * URI-less MediaItem straight to ExoPlayer (they are resolved by the
     * library callback instead), and drops external queue mutations so the
     * coordinator-owned queue stays the single source of truth. Own-controller
     * writes are always fully-resolved (URI present) and pass through
     * untouched — the mobile playback path is byte-for-byte the pre-M14 one.
     */
    private inner class UriLessWriteFilter
    @UnstableApi constructor(private val wrapped: Player) : ForwardingPlayer(wrapped) {

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .remove(Player.COMMAND_SET_MEDIA_ITEM)
                .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .build()

        override fun setMediaItem(mediaItem: MediaItem) {
            if (mediaItem.localConfiguration?.uri == null) return
            super.setMediaItem(mediaItem)
        }

        override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
            if (mediaItem.localConfiguration?.uri == null) return
            super.setMediaItem(mediaItem, startPositionMs)
        }

        override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
            if (mediaItem.localConfiguration?.uri == null) return
            super.setMediaItem(mediaItem, resetPosition)
        }

        override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
            if (mediaItems.any { it.localConfiguration?.uri == null }) return
            super.setMediaItems(mediaItems)
        }

        override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
            if (mediaItems.any { it.localConfiguration?.uri == null }) return
            super.setMediaItems(mediaItems, resetPosition)
        }

        override fun setMediaItems(
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ) {
            if (mediaItems.any { it.localConfiguration?.uri == null }) return
            super.setMediaItems(mediaItems, startIndex, startPositionMs)
        }

        override fun addMediaItem(index: Int, mediaItem: MediaItem) {
            if (mediaItem.localConfiguration?.uri == null) return
            super.addMediaItem(index, mediaItem)
        }

        override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
            if (mediaItems.any { it.localConfiguration?.uri == null }) return
            super.addMediaItems(index, mediaItems)
        }

        override fun moveMediaItem(fromIndex: Int, toIndex: Int) = Unit

        override fun removeMediaItem(index: Int) = Unit

        override fun removeMediaItems(fromIndex: Int, toIndex: Int) = Unit

        override fun clearMediaItems() = Unit

        override fun seekToDefaultPosition() = Unit

        override fun seekToDefaultPosition(mediaItemIndex: Int) = Unit

        override fun seekToNextMediaItem() = Unit

        override fun seekToPreviousMediaItem() = Unit

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) = Unit
    }

    /**
     * The MediaLibrarySession callback: browse tree + playback resolution.
     * All repository/state access goes through the app-supplied providers —
     * this class contains no playback logic of its own.
     */
    private inner class NaudioLibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(MediaItemMapper.folderItem(MediaItemMapper.ROOT_MEDIA_ID, ROOT_TITLE), params),
            )

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return when (val id = MediaItemMapper.decode(mediaId)) {
                MediaItemMapper.MediaId.Favorites -> immediateItem(
                    MediaItemMapper.folderItem(MediaItemMapper.FAVORITES_MEDIA_ID, FAVORITES_TITLE),
                )
                MediaItemMapper.MediaId.Playlists -> immediateItem(
                    MediaItemMapper.folderItem(MediaItemMapper.PLAYLISTS_MEDIA_ID, PLAYLISTS_TITLE),
                )
                is MediaItemMapper.MediaId.Playlist -> fetchItem() {
                    browseTree?.observePlaylists()?.first()
                        ?.firstOrNull { it.playlistId == id.playlistId }
                        ?.let { node ->
                            MediaItemMapper.playlistItem(node.playlistId, node.name, node.trackCount)
                        }
                }
                is MediaItemMapper.MediaId.Track -> fetchItem() {
                    bridge?.findTrack(id.providerId, id.trackId)
                        ?.let { MediaItemMapper.trackMediaItem(it, mediaId) }
                }
                else -> Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val id = MediaItemMapper.decode(parentId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            return when (id) {
                MediaItemMapper.MediaId.Root -> Futures.immediateFuture(
                    LibraryResult.ofItemList(
                        ImmutableList.of(
                            MediaItemMapper.folderItem(MediaItemMapper.FAVORITES_MEDIA_ID, FAVORITES_TITLE),
                            MediaItemMapper.folderItem(MediaItemMapper.PLAYLISTS_MEDIA_ID, PLAYLISTS_TITLE),
                        ),
                        params,
                    ),
                )
                MediaItemMapper.MediaId.Favorites -> fetchList(params) {
                    browseTree?.observeFavorites()?.first()?.map { track ->
                        MediaItemMapper.trackMediaItem(
                            track,
                            MediaItemMapper.trackIdOf(
                                track.providerId,
                                track.id,
                                MediaItemMapper.FAVORITES_CONTEXT,
                            ),
                        )
                    } ?: emptyList()
                }
                MediaItemMapper.MediaId.Playlists -> fetchList(params) {
                    browseTree?.observePlaylists()?.first()
                        ?.map { node ->
                            MediaItemMapper.playlistItem(node.playlistId, node.name, node.trackCount)
                        }
                        .orEmpty()
                }
                is MediaItemMapper.MediaId.Playlist -> fetchList(params) {
                    browseTree?.observePlaylistTracks(id.playlistId)?.first()?.map { track ->
                        MediaItemMapper.trackMediaItem(
                            track,
                            MediaItemMapper.trackIdOf(track.providerId, track.id, id.playlistId),
                        )
                    } ?: emptyList()
                }
                is MediaItemMapper.MediaId.Track ->
                    Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
        }

        /**
         * Android Auto playback request. The requested track's whole
         * collection is enqueued through the bridge (see
         * [resolvePlayableItems]) and the session acknowledges with the
         * URI-less requested item, which the URI-less-write guard keeps away
         * from the player — only the coordinator's resolved load plays.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> =
            fetchItemList { resolvePlayableItems(mediaItems) }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            browser: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            fetchItemsWithStartPosition {
                val playable = resolvePlayableItems(mediaItems)
                val clampedStart =
                    if (playable.isEmpty() || startIndex !in playable.indices) 0 else startIndex
                MediaSession.MediaItemsWithStartPosition(playable, clampedStart, startPositionMs)
            }

        /**
         * The one playback pathway: media id → [AutoPlaybackRequestResolver]
         * → [AutoPlaybackBridge.playCollection] → the shared coordinator's
         * setQueue — the exact entry point the mobile UI plays from
         * (persistent queue, just-in-time provider resolution, one player).
         * The session acknowledges with the requested URI-less item; the
         * URI-less-write guard guarantees it can never reach the player, so
         * the coordinator's resolved load is the only media Media3 ever sees.
         * Returns an empty list when nothing resolves (never an error).
         */
        private suspend fun resolvePlayableItems(
            mediaItems: List<MediaItem>,
        ): List<MediaItem> {
            for (requested in mediaItems) {
                val outcome = requestResolver?.resolve(requested.mediaId)
                    ?: return emptyList()
                if (outcome is AutoPlaybackRequestResolver.Outcome.Delegated) {
                    // Acknowledge with the requested (URI-less) item: the
                    // guard drops it at the player, where the coordinator's
                    // resolved load lands instead.
                    return listOf(requested)
                }
            }
            return emptyList()
        }

        /** Delegated queue movement — the shared coordinator owns the queue. */
        @Deprecated("The only command-interception point in Media3 1.11.x")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            command: Int,
        ): Int =
            when (command) {
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    bridge?.skipToNext()
                    SessionResult.RESULT_INFO_SKIPPED
                }
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    bridge?.skipToPrevious()
                    SessionResult.RESULT_INFO_SKIPPED
                }
                else -> super.onPlayerCommandRequest(session, controller, command)
            }

        // ------------------------------------------------------------------
        // helpers
        // ------------------------------------------------------------------

        private fun immediateItem(item: MediaItem): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(item, /* params = */ null))

        /** Runs [block] on the library executor; errors surface as BAD_VALUE. */
        private fun fetchItem(
            block: suspend () -> MediaItem?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            sessionScope.launch(libraryDispatcher) {
                try {
                    val item = block()
                    future.set(
                        if (item != null) LibraryResult.ofItem(item, null)
                        else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
                    )
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    future.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                }
            }
            return future
        }

        /** Runs [block] on the library executor; errors surface as BAD_VALUE. */
        private fun fetchList(
            params: MediaLibraryService.LibraryParams?,
            block: suspend () -> List<MediaItem>,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            sessionScope.launch(libraryDispatcher) {
                try {
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(block()), params))
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    future.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                }
            }
            return future
        }

        /** Runs [block] on the library executor; errors surface as an empty set. */
        private fun fetchItemList(
            block: suspend () -> List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            val future = SettableFuture.create<List<MediaItem>>()
            sessionScope.launch(libraryDispatcher) {
                try {
                    future.set(block())
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    future.set(emptyList())
                }
            }
            return future
        }

        /** Variant of [fetchItemList] for set-media-items; errors fail the future. */
        private fun fetchItemsWithStartPosition(
            block: suspend () -> MediaSession.MediaItemsWithStartPosition,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            sessionScope.launch(libraryDispatcher) {
                try {
                    future.set(block())
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    future.setException(t)
                }
            }
            return future
        }        /**
         * Dispatcher for the library callbacks: their suspending blocks run on
         * the single [libraryExecutor], keeping the Media3 contract (heavy
         * work off the main/session thread) while result plumbing stays on
         * the session scope.
         */
        private val libraryDispatcher =
            libraryExecutor.asCoroutineDispatcher()
    }

    private companion object {
        const val HTTP_CONNECT_TIMEOUT_MS = 10_000
        const val HTTP_READ_TIMEOUT_MS = 15_000
        const val ROOT_TITLE = "Naudio"
        const val FAVORITES_TITLE = "Favorites"
        const val PLAYLISTS_TITLE = "Playlists"
    }
}
