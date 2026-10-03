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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
 *    app-supplied [AutoBrowseTreeProvider] (favorites / playlists / history).
 *  - playback:  External (Android Auto / Assistant / Bluetooth) requests arrive
 *    WITHOUT a URI. The callback resolves the id via the app-supplied
 *    [AutoPlaybackBridge] — which delegates through the existing
 *    PlaybackCoordinator's resolution path — and returns playable MediaItems
 *    to Media3. There is exactly one player, one queue and one coordinator;
 *    nothing Android-Auto-specific is created.
 *  - routing:   the session's [Player] is wrapped in [UriLessWriteFilter] so a
 *    write that is not playable by itself (URI-less, or empty) can never reach
 *    ExoPlayer, whatever the controller asked for.
 *
 * M18 restores the mobile write path:
 *
 *  - [UriLessWriteFilter] no longer masks COMMAND_SET_MEDIA_ITEM /
 *    COMMAND_CHANGE_MEDIA_ITEMS out of [Player.getAvailableCommands]. Media3
 *    intersects every controller's commands with the player's, so the global
 *    mask silently blocked the app's OWN MediaControllerPlaybackController
 *    before the request ever reached this session. Its safety purpose is kept
 *    by the per-write guards (see [UriLessWriteFilter]).
 *  - [NaudioLibraryCallback] branches [MediaSession.Callback.onSetMediaItems] /
 *    [MediaSession.Callback.onAddMediaItems] on the controller's real package
 *    identity ([MediaSession.ControllerInfo.getPackageName], the same signal
 *    Media3 itself uses). The app's own controller keeps its normal
 *    URI-bearing write; every other controller stays on the Auto resolver path.
 */
@UnstableApi
class NaudioPlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null

    /** Session-lifetime scope for async library callbacks. */
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Single-threaded executor for library-result callbacks; shut down in onDestroy. */
    private val libraryExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        val dependencies = application as? PlaybackDependenciesProvider
        check(dependencies != null) {
            "NaudioPlaybackService requires the Application to implement PlaybackDependenciesProvider"
        }
        val browseTree = dependencies.autoBrowseTree
        val bridge = dependencies.autoPlaybackBridge
        val requestResolver = AutoPlaybackRequestResolver(
            browseTree = browseTree,
            bridge = bridge,
        )
        val player = ExoPlayer.Builder(this)
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
        val libraryCallback = NaudioLibraryCallback(
            appPackageName = packageName,
            browseTree = browseTree,
            bridge = bridge,
            requestResolver = requestResolver,
            scope = sessionScope,
            libraryDispatcher = libraryExecutor.asCoroutineDispatcher(),
        )
        mediaLibrarySession = MediaLibrarySession.Builder(this, UriLessWriteFilter(player), libraryCallback)
            .setSessionActivity(sessionActivity)
            .build()
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

    private companion object {
        const val HTTP_CONNECT_TIMEOUT_MS = 10_000
        const val HTTP_READ_TIMEOUT_MS = 15_000
    }
}

/**
 * The last line of defence for ExoPlayer: a write that cannot be played on its
 * own never reaches the player.
 *
 * Two kinds of write are dropped:
 *  - URI-less items. Android Auto and every other external browser send browse
 *    ids without a URI; those requests are answered by
 *    [NaudioLibraryCallback] delegating through [AutoPlaybackBridge] to the
 *    shared coordinator, which loads fully resolved items itself. The URI-less
 *    acknowledgement the session sends back is therefore dropped here. Every
 *    write overload Media3's session stub can apply is covered: setMediaItem(s),
 *    addMediaItem(s) and replaceMediaItem(s), including the un-indexed forms that
 *    [ForwardingPlayer] forwards without re-dispatching.
 *  - empty item lists. They carry nothing playable, and forwarding one would let
 *    an unresolved request wipe the coordinator-owned timeline.
 *
 * Queue mutations stay no-ops so the coordinator remains the single source of
 * truth for the queue.
 *
 * M18: this filter deliberately does NOT override
 * [getAvailableCommands]. Media3 intersects a controller's available commands
 * with the player's ([MediaSession.Callback.onConnect]), so removing
 * COMMAND_SET_MEDIA_ITEM / COMMAND_CHANGE_MEDIA_ITEMS here silently blocked
 * the app's own controller before its request ever reached this session. The
 * per-write guards above — not a global command mask — are what keep an
 * unplayable item away from ExoPlayer.
 */
@UnstableApi
internal class UriLessWriteFilter(private val wrapped: Player) : ForwardingPlayer(wrapped) {

    override fun setMediaItem(mediaItem: MediaItem) {
        if (!isPlayable(mediaItem)) return
        super.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        if (!isPlayable(mediaItem)) return
        super.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        if (!isPlayable(mediaItem)) return
        super.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        if (!isPlayable(mediaItems)) return
        super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        if (!isPlayable(mediaItems)) return
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) {
        if (!isPlayable(mediaItems)) return
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        if (!isPlayable(mediaItem)) return
        super.addMediaItem(mediaItem)
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        if (!isPlayable(mediaItem)) return
        super.addMediaItem(index, mediaItem)
    }

    /**
     * Guarded separately from the indexed overload on purpose:
     * [ForwardingPlayer.addMediaItems] delegates straight to the wrapped player
     * instead of re-dispatching to the indexed form, so it has to be overridden
     * in its own right. This is the overload Media3's session stub actually
     * calls when it applies a resolved `onAddMediaItems` result.
     */
    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
        if (!isPlayable(mediaItems)) return
        super.addMediaItems(mediaItems)
    }

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        if (!isPlayable(mediaItems)) return
        super.addMediaItems(index, mediaItems)
    }

    // Same reasoning: Media3's session stub applies a resolved
    // `onAddMediaItems` result through replaceMediaItem/replaceMediaItems, and
    // ForwardingPlayer hands both to the wrapped player unchecked.
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        if (!isPlayable(mediaItem)) return
        super.replaceMediaItem(index, mediaItem)
    }

    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>) {
        if (!isPlayable(mediaItems)) return
        super.replaceMediaItems(fromIndex, toIndex, mediaItems)
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

    /** A single item is playable only when it carries a URI. */
    private fun isPlayable(mediaItem: MediaItem): Boolean =
        mediaItem.localConfiguration?.uri != null

    /** A list is playable only when it is non-empty and every item has a URI. */
    private fun isPlayable(mediaItems: List<MediaItem>): Boolean =
        mediaItems.isNotEmpty() && mediaItems.all { it.localConfiguration?.uri != null }
}

/**
 * The MediaLibrarySession callback: browse tree + playback resolution.
 * All repository/state access goes through the app-supplied providers —
 * this class contains no playback logic of its own.
 */
internal class NaudioLibraryCallback(
    /** This app's package, i.e. the identity of NaudioPlaybackService's own process. */
    private val appPackageName: String,
    private val browseTree: AutoBrowseTreeProvider,
    private val bridge: AutoPlaybackBridge,
    private val requestResolver: AutoPlaybackRequestResolver,
    private val scope: CoroutineScope,
    private val libraryDispatcher: CoroutineDispatcher,
) : MediaLibraryService.MediaLibrarySession.Callback {

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            LibraryResult.ofItem(MediaItemMapper.folderItem(MediaItemMapper.ROOT_MEDIA_ID, ROOT_TITLE), params),
        )

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
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
            MediaItemMapper.MediaId.History -> immediateItem(
                MediaItemMapper.folderItem(MediaItemMapper.HISTORY_MEDIA_ID, HISTORY_TITLE),
            )
            is MediaItemMapper.MediaId.Playlist -> fetchItem {
                browseTree.observePlaylists().first()
                    .firstOrNull { it.playlistId == id.playlistId }
                    ?.let { node ->
                        MediaItemMapper.playlistItem(node.playlistId, node.name, node.trackCount)
                    }
            }
            is MediaItemMapper.MediaId.Track -> fetchItem {
                bridge.findTrack(id.providerId, id.trackId)
                    ?.let { MediaItemMapper.trackMediaItem(it, mediaId) }
            }
            else -> Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
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
                        // M17: same HistoryRepository the Home row reads.
                        MediaItemMapper.folderItem(MediaItemMapper.HISTORY_MEDIA_ID, HISTORY_TITLE),
                    ),
                    params,
                ),
            )
            MediaItemMapper.MediaId.Favorites -> fetchList(params) {
                browseTree.observeFavorites().first().map { track ->
                    MediaItemMapper.trackMediaItem(
                        track,
                        MediaItemMapper.trackIdOf(
                            track.providerId,
                            track.id,
                            MediaItemMapper.FAVORITES_CONTEXT,
                        ),
                    )
                }
            }
            MediaItemMapper.MediaId.Playlists -> fetchList(params) {
                browseTree.observePlaylists().first()
                    .map { node ->
                        MediaItemMapper.playlistItem(node.playlistId, node.name, node.trackCount)
                    }
            }
            is MediaItemMapper.MediaId.Playlist -> fetchList(params) {
                browseTree.observePlaylistTracks(id.playlistId).first().map { track ->
                    MediaItemMapper.trackMediaItem(
                        track,
                        MediaItemMapper.trackIdOf(track.providerId, track.id, id.playlistId),
                    )
                }
            }
            // M17: history nodes render from their stored metadata snapshot
            // (no provider lookup, no network) and carry the history context
            // so selecting one enqueues the recent-history list through the
            // existing resolver -> bridge -> coordinator path.
            MediaItemMapper.MediaId.History -> fetchList(params) {
                browseTree.observeRecentHistory().first().map { track ->
                    MediaItemMapper.trackMediaItem(
                        track,
                        MediaItemMapper.trackIdOf(
                            track.providerId,
                            track.id,
                            MediaItemMapper.HISTORY_CONTEXT,
                        ),
                    )
                }
            }
            is MediaItemMapper.MediaId.Track ->
                Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
        }
    }

    /**
     * M18: the app's own [com.naudio.core.player.MediaControllerPlaybackController]
     * already writes fully resolved, URI-bearing items, so its request is handed
     * straight back and ExoPlayer plays it through the normal mobile path. Every
     * other controller (Android Auto, Assistant, Bluetooth) stays on the Android
     * Auto URI-less resolution path below.
     *
     * The distinction uses Media3's own controller identity —
     * [MediaSession.ControllerInfo.getPackageName] — which MediaSession validates
     * against the caller's UID when the connection is accepted
     * (`SessionUtil.checkPackageValidity`), so it cannot be spoofed by another
     * app. Anything that is not this package (including the unverified
     * `android.media.session.MediaController` placeholder) fails closed onto the
     * Auto path.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> =
        if (isAppController(controller)) {
            Futures.immediateFuture(mediaItems)
        } else {
            fetchItemList { resolvePlayableItems(mediaItems) }
        }

    /** M18: see [onAddMediaItems] — identical controller split for set-items. */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        browser: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        if (isAppController(browser)) {
            Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    mediaItems,
                    clampStartIndex(startIndex, mediaItems.size),
                    startPositionMs,
                ),
            )
        } else {
            fetchItemsWithStartPosition {
                val playable = resolvePlayableItems(mediaItems)
                MediaSession.MediaItemsWithStartPosition(
                    playable,
                    clampStartIndex(startIndex, playable.size),
                    startPositionMs,
                )
            }
        }

    /** True only for a controller running in this app's own process. */
    private fun isAppController(controller: MediaSession.ControllerInfo): Boolean =
        controller.packageName == appPackageName

    /** Keeps Media3's own `C.INDEX_UNSET`, and never points past the list. */
    private fun clampStartIndex(startIndex: Int, itemCount: Int): Int =
        if (startIndex == C.INDEX_UNSET || startIndex in 0 until itemCount) startIndex else 0

    /**
     * The one external playback pathway: media id → [AutoPlaybackRequestResolver]
     * → [AutoPlaybackBridge.playCollection] → the shared coordinator's
     * setQueue — the exact entry point the mobile UI plays from
     * (persistent queue, just-in-time provider resolution, one player).
     * The session acknowledges with the requested URI-less item; the
     * [UriLessWriteFilter] guarantees it can never reach the player, so
     * the coordinator's resolved load is the only media Media3 ever sees.
     * Returns an empty list when nothing resolves (never an error).
     */
    private suspend fun resolvePlayableItems(
        mediaItems: List<MediaItem>,
    ): List<MediaItem> {
        for (requested in mediaItems) {
            val outcome = requestResolver.resolve(requested.mediaId)
            if (outcome is AutoPlaybackRequestResolver.Outcome.Delegated) {
                // Acknowledge with the requested (URI-less) item: the
                // filter drops it at the player, where the coordinator's
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
                bridge.skipToNext()
                SessionResult.RESULT_INFO_SKIPPED
            }
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                bridge.skipToPrevious()
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
        scope.launch(libraryDispatcher) {
            try {
                val item = block()
                future.set(
                    if (item != null) LibraryResult.ofItem(item, null)
                    else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
                )
            } catch (cancellation: CancellationException) {
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
        scope.launch(libraryDispatcher) {
            try {
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(block()), params))
            } catch (cancellation: CancellationException) {
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
        scope.launch(libraryDispatcher) {
            try {
                future.set(block())
            } catch (cancellation: CancellationException) {
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
        scope.launch(libraryDispatcher) {
            try {
                future.set(block())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                future.setException(t)
            }
        }
        return future
    }

    private companion object {
        const val ROOT_TITLE = "Naudio"
        const val FAVORITES_TITLE = "Favorites"
        const val PLAYLISTS_TITLE = "Playlists"

        /** M17: the Android Auto label for the Recently Played folder. */
        const val HISTORY_TITLE = "Recently Played"
    }
}