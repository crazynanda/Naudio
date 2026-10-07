package com.naudio.provider.ytmusic

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.provider.api.Page
import com.naudio.provider.innertube.api.YtMusicAlbum
import com.naudio.provider.innertube.api.YtMusicArtist
import com.naudio.provider.innertube.api.YtMusicBackend
import com.naudio.provider.innertube.api.YtMusicHome
import com.naudio.provider.innertube.api.YtMusicPlaylist

/**
 * Recording [YtMusicBackend] double.
 *
 * This is the seam the provider tests actually use: it lets the provider's own
 * responsibilities — token discipline, delegation, error propagation — be
 * asserted without a network, while [YtMusicProviderIntegrationTest] separately
 * proves the real InnerTube implementation satisfies the same contract.
 *
 * Every operation is recorded so a test can assert what the provider asked for.
 */
internal class FakeYtMusicBackend(
    private val page: Page<Track> = Page(emptyList(), null),
    private val song: Track? = null,
    private val source: AudioSource? = null,
    /** M21: catalog pages the provider maps. Null models a "not found". */
    private val artist: YtMusicArtist? = null,
    private val album: YtMusicAlbum? = null,
) : YtMusicBackend {

    val calls = mutableListOf<String>()
    var lastQuery: String? = null
    var lastContinuation: String? = null

    /** M21: the browse ids the provider actually asked for. */
    var lastArtistBrowseId: String? = null
    var lastAlbumBrowseId: String? = null

    /** M21: set to fail the next catalog call, to exercise error propagation. */
    var catalogError: Exception? = null

    override suspend fun search(query: String, continuation: String?): Page<Track> {
        if (query.isBlank()) return Page(emptyList(), null)
        calls.add("search")
        lastQuery = query
        lastContinuation = continuation
        return page
    }

    override suspend fun home(): YtMusicHome {
        calls.add("home")
        return YtMusicHome()
    }

    override suspend fun song(videoId: String): Track? {
        calls.add("song")
        return song
    }

    override suspend fun artist(browseId: String, continuation: String?): YtMusicArtist {
        calls.add("artist")
        lastArtistBrowseId = browseId
        catalogError?.let { throw it }
        return artist ?: YtMusicArtist(id = browseId, name = "A")
    }

    override suspend fun album(browseId: String, continuation: String?): YtMusicAlbum {
        calls.add("album")
        lastAlbumBrowseId = browseId
        catalogError?.let { throw it }
        return album ?: YtMusicAlbum(id = browseId, title = "Al")
    }

    override suspend fun playlist(browseId: String, continuation: String?): YtMusicPlaylist {
        calls.add("playlist")
        return YtMusicPlaylist(id = browseId, title = "Pl")
    }

    override suspend fun related(videoId: String, continuation: String?): Page<Track> {
        calls.add("related")
        return page
    }

    override suspend fun resolvePlayback(track: Track): AudioSource? {
        // Mirrors the real backend's exact-routing contract, so provider tests
        // see the same behaviour they will see in production.
        if (track.providerId != YtMusicBackend.PROVIDER_ID) return null
        calls.add("resolvePlayback")
        return source
    }

    companion object {
        fun track(id: String) = Track(id = id, providerId = "ytmusic", title = "T$id", artist = "A")
    }
}