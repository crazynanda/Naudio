package com.naudio.provider.innertube

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.provider.api.Page
import com.naudio.provider.api.PageToken
import com.naudio.provider.innertube.api.YtMusicAlbum
import com.naudio.provider.innertube.api.YtMusicArtist
import com.naudio.provider.innertube.api.YtMusicBackend
import com.naudio.provider.innertube.api.YtMusicHome
import com.naudio.provider.innertube.api.YtMusicPlaylist
import com.naudio.provider.innertube.client.InnerTubeClient
import com.naudio.provider.innertube.parser.InnerTubeBrowseParser
import com.naudio.provider.innertube.parser.InnerTubePlayerParser
import com.naudio.provider.innertube.parser.InnerTubeSearchParser
import com.naudio.provider.innertube.parser.InnerTubeWatchParser
import com.naudio.provider.innertube.request.InnerTubeRequest
import io.ktor.client.HttpClient

/**
 * The InnerTube implementation of [YtMusicBackend] — the only class in Naudio
 * that knows what an InnerTube endpoint is.
 *
 * This class is the whole of the backend. It is intentionally thin: it picks
 * the right request for each operation, hands the raw response to the right
 * parser, and converts the parser's result into the domain types the boundary
 * promises. It holds no state, owns no cache, and creates no HTTP client of its
 * own — the shared application-lifetime [HttpClient] is injected and reused.
 *
 * ## The access-control boundary
 *
 * Nothing here evades an access control. In particular
 * [resolvePlayback] asks the anonymous public client for a stream and accepts
 * only a URL the server handed over verbatim under an `OK` playability status;
 * ciphered streams are never deprotected, no client identity is switched to
 * obtain a more permissive answer, and no token is minted. When YouTube declines
 * to serve an anonymous stream — the normal case for catalog audio — the honest
 * answer `null` is returned and Naudio surfaces its existing "source
 * unavailable" state. See `InnerTubePlayerParser` for the full enumeration.
 *
 * All requests are anonymous: no cookies, OAuth tokens, PO tokens or
 * user-agent overrides are ever set.
 */
class InnerTubeYtMusicBackend(
    httpClient: HttpClient,
) : YtMusicBackend {

    private val client = InnerTubeClient(httpClient)

    override suspend fun search(query: String, continuation: String?): Page<Track> {
        if (query.isBlank()) return Page(emptyList(), nextToken = null)
        return client.execute(InnerTubeRequest.Search(query, continuation), InnerTubeSearchParser::parse)
    }

    override suspend fun home(): YtMusicHome =
        client.execute(InnerTubeRequest.Browse(HOME_BROWSE_ID), InnerTubeBrowseParser::parseHome)

    override suspend fun song(videoId: String): Track? {
        if (videoId.isBlank()) return null
        return client.execute(InnerTubeRequest.Next(videoId), InnerTubeWatchParser::parseSong)
    }

    override suspend fun artist(browseId: String, continuation: String?): YtMusicArtist =
        client.execute(
            InnerTubeRequest.Browse(browseId, continuation = continuation),
        ) { InnerTubeBrowseParser.parseArtist(browseId, it) }

    override suspend fun album(browseId: String, continuation: String?): YtMusicAlbum =
        client.execute(
            InnerTubeRequest.Browse(browseId, continuation = continuation),
        ) { InnerTubeBrowseParser.parseAlbum(browseId, it) }

    override suspend fun playlist(browseId: String, continuation: String?): YtMusicPlaylist =
        client.execute(
            InnerTubeRequest.Browse(browseId, continuation = continuation),
        ) { InnerTubeBrowseParser.parsePlaylist(browseId, it) }

    override suspend fun related(videoId: String, continuation: String?): Page<Track> {
        if (videoId.isBlank()) return Page(emptyList(), nextToken = null)
        val page = client.execute(InnerTubeRequest.Next(videoId), InnerTubeWatchParser::parseRelated)
        // A related-rail cursor is never a Next-request field: the watch endpoint
        // has no continuation, so a token handed out by this backend can only be
        // replayed against a browse. Rather than silently send a cursor the
        // endpoint cannot use, an unusable token is reported as no more pages.
        if (continuation != null && continuation != (page.nextToken as? PageToken.Opaque)?.value) {
            return Page(page.items, nextToken = null)
        }
        return page
    }

    /**
     * Ask the anonymous playback-resolution endpoint for [track].
     *
     * Returns [AudioSource.Remote] ONLY when the backend itself served a
     * directly-playable URL. Any other outcome — not playable, not entitled,
     * ciphered formats, no audio at all — is `null`, which Naaudio treats as
     * "this provider cannot play it" and surfaces as its normal unavailable
     * state. No URL is ever fabricated, and this method never throws to
     * "signal" an unusable stream.
     */
    override suspend fun resolvePlayback(track: Track): AudioSource? {
        // Routing is exact: only tracks this backend produced are resolved.
        if (track.providerId != YtMusicBackend.PROVIDER_ID) return null
        if (track.id.isBlank()) return null
        val url = client.execute(InnerTubeRequest.Player(track.id), InnerTubePlayerParser::parse)
            .directAudioUrl
            ?: return null
        return AudioSource.Remote(url)
    }

    private companion object {
        /** InnerTube's browse id for the YouTube Music home feed. */
        const val HOME_BROWSE_ID = "FEmusic_home"
    }
}