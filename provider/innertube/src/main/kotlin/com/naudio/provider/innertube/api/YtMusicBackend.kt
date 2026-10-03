package com.naudio.provider.innertube.api

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.provider.api.Page

/**
 * The one and only way Naudio is allowed to talk to the YouTube Music backend.
 *
 * This is the *black-box* boundary of the M19 architecture:
 *
 * ```
 * Naudio -> YtMusicBackend -> InnerTube implementation -> YT Music
 * ```
 *
 * Callers request catalog and playback OPERATIONS and receive domain objects
 * ([Track], [AudioSource], [Page]) plus the browse-shaped [YtMusicCatalog]
 * types below. Nothing here exposes an endpoint, a JSON envelope, a request
 * body, a renderer name, or any other InnerTube-specific concept: the whole
 * point of the interface is that a swap of backend implementation is invisible
 * to `provider/ytmusic`, `:data`, `:app` and `:core`.
 *
 * Contract shared by every operation:
 *  - Transport, HTTP-status and decoding failures throw
 *    [com.naudio.core.network.NetworkException]. Cancellation always propagates
 *    untouched and is never converted into a failure value.
 *  - "The backend has nothing for this id" is NOT a failure: it is `null` (or an
 *    empty [Page]), never a thrown exception.
 *  - Every paging operation hands out an opaque [com.naudio.provider.api.PageToken.Opaque]
 *    and accepts nothing else. An incompatible token is a caller error and
 *    fails deterministically — it is never silently reinterpreted.
 */
interface YtMusicBackend {

    /**
     * Search the YouTube Music catalog, songs-filtered.
     *
     * @param continuation an opaque token from a previous page's
     *   [Page.nextToken], or null for the first page.
     */
    suspend fun search(query: String, continuation: String? = null): Page<Track>

    /** The YouTube Music home feed, as ordered shelves of tracks. */
    suspend fun home(): YtMusicHome

    /**
     * Full metadata for one song, identified by its video id — the same id
     * [Track.id] carries for every track this backend emits. Null when the
     * backend has no such song.
     */
    suspend fun song(videoId: String): Track?

    /** One artist page: identity, description and the artist's songs. */
    suspend fun artist(browseId: String, continuation: String? = null): YtMusicArtist

    /** One album page: identity, release metadata and its ordered track list. */
    suspend fun album(browseId: String, continuation: String? = null): YtMusicAlbum

    /** One playlist page: identity, author and its ordered track list. */
    suspend fun playlist(browseId: String, continuation: String? = null): YtMusicPlaylist

    /** Tracks related to [videoId], used for the "more like this" surface. */
    suspend fun related(videoId: String, continuation: String? = null): Page<Track>

    /**
     * Resolve the playable source for [track], or null when the backend cannot
     * legitimately serve it.
     *
     * Null is a first-class, expected answer, NOT a bug and NOT a disguised
     * error: the caller ([com.naudio.provider.api.PlaybackProvider.resolve])
     * reports it as an unavailable source. Implementations must never fabricate
     * a URL, and must never return a URL they did not receive verbatim from the
     * backend.
     */
    suspend fun resolvePlayback(track: Track): AudioSource?

    companion object {
        /**
         * Provider id stamped onto every [Track] this backend emits, and the id
         * under which its playback capability is registered. Single source of
         * truth for both the metadata and the playback side.
         */
        const val PROVIDER_ID: String = "ytmusic"
    }
}
