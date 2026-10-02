package com.naudio.core.player

import com.naudio.core.model.Track
import kotlinx.coroutines.flow.first

/**
 * Turns an Android Auto media-id play request into a delegation to the
 * existing playback path: the requested track's whole collection (playlist,
 * favorites or — M17 — recent history, in repository order) is handed to
 * [AutoPlaybackBridge.playCollection] at the tapped index — the same entry
 * point the mobile UI uses, so the persistent queue, just-in-time resolution
 * and the single player all remain authoritative.
 *
 * Deliberately free of Media3 types so the delegation behavior is unit-
 * testable without an Android device; the service maps [Outcome] onto its
 * ListenableFuture results.
 */
class AutoPlaybackRequestResolver(
    private val browseTree: AutoBrowseTreeProvider,
    private val bridge: AutoPlaybackBridge,
) {

    /** Result of handling one play request. */
    sealed interface Outcome {
        /**
         * The request was delegated to the bridge. [acknowledgeMediaId] is the
         * id the session should answer with (kept URI-less by design: the
         * session's write guard prevents it reaching the player directly).
         */
        data class Delegated(val acknowledgeMediaId: String) : Outcome

        /** The id is not a track, or the track is not in the browsable library. */
        data object NotFound : Outcome
    }

    /**
     * Handle a play request for [mediaId]: look up the track's collection and
     * position, then delegate through the bridge. Malformed/unknown ids yield
     * [Outcome.NotFound] — never an exception.
     */
    suspend fun resolve(mediaId: String?): Outcome {
        val trackId = MediaItemMapper.decode(mediaId)
            as? MediaItemMapper.MediaId.Track ?: return Outcome.NotFound
        val (collection, index) = when (trackId.context) {
            MediaItemMapper.TrackContext.PLAYLIST -> {
                val playlistId = trackId.playlistId ?: return Outcome.NotFound
                val tracks = browseTree.observePlaylistTracks(playlistId).first()
                tracks to tracks.indexOfFirst {
                    it.providerId == trackId.providerId && it.id == trackId.trackId
                }
            }
            MediaItemMapper.TrackContext.FAVORITES -> {
                val tracks = browseTree.observeFavorites().first()
                tracks to tracks.indexOfFirst {
                    it.providerId == trackId.providerId && it.id == trackId.trackId
                }
            }
            // M17: a history node enqueues the recent-history list, exactly the
            // way a favorites node enqueues favorites.
            MediaItemMapper.TrackContext.HISTORY -> {
                val tracks = browseTree.observeRecentHistory().first()
                tracks to tracks.indexOfFirst {
                    it.providerId == trackId.providerId && it.id == trackId.trackId
                }
            }
            MediaItemMapper.TrackContext.NONE -> return Outcome.NotFound
        }
        if (index < 0) return Outcome.NotFound
        bridge.playCollection(collection, index)
        return Outcome.Delegated(acknowledgeMediaId = mediaId.orEmpty())
    }
}
