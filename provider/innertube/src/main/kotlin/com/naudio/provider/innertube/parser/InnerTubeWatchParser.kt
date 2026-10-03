package com.naudio.provider.innertube.parser

import com.naudio.core.model.Track
import com.naudio.provider.api.Page
import com.naudio.provider.api.PageToken
import com.naudio.provider.innertube.api.YtMusicBackend
import com.naudio.provider.innertube.parser.InnerTubeJson.array
import com.naudio.provider.innertube.parser.InnerTubeJson.obj
import com.naudio.provider.innertube.parser.InnerTubeJson.str
import kotlinx.serialization.json.JsonObject

/**
 * Parses the InnerTube *watch* page — the one request that answers two
 * questions Naudio asks: "what is this song?" and "what is similar to it?".
 *
 * Song metadata comes from the response's own `videoDetails` block (title,
 * author, length, thumbnail), which is plain descriptive data and not tied to
 * any stream protection. Related tracks come from the watch-next results rail.
 *
 * Nothing here reads stream or format data: the watch page is metadata only.
 */
internal object InnerTubeWatchParser {

    private const val WATCH_NEXT = "singleColumnMusicWatchNextResultsRenderer"

    /**
     * Single-song metadata, or null when the response carries no `videoDetails`
     * (e.g. a removed or private video).
     */
    fun parseSong(body: String): Track? {
        val details = InnerTubeJson.parseObject(body)?.obj("videoDetails") ?: return null
        val videoId = details.str("videoId") ?: return null
        return Track(
            id = videoId,
            providerId = YtMusicBackend.PROVIDER_ID,
            title = details.str("title")?.takeIf { it.isNotBlank() } ?: UNKNOWN_TITLE,
            artist = details.str("author")?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST,
            album = details.str("album")?.takeIf { it.isNotBlank() },
            artworkUrl = artwork(details),
            durationMs = details.str("lengthSeconds")?.toLongOrNull()?.times(1000L) ?: 0L,
        )
    }

    /**
     * The related rail as one page of tracks, or an empty page when the
     * response carries no rail.
     */
    fun parseRelated(body: String): Page<Track> {
        val root = InnerTubeJson.parseObject(body) ?: return Page(emptyList(), null)
        val rail = InnerTubeJson.findByKey(root, "related")
            ?: InnerTubeJson.findByKey(root, WATCH_NEXT)
            ?: return Page(emptyList(), null)
        // The related rail's items live directly in `endItems`; some responses wrap
        // them one level deeper under a `contents` object.
        val items = rail.array("endItems")
            ?: rail.obj("endItems")?.array("contents")
            ?: rail.array("contents")
            ?: return Page(emptyList(), null)
        val tracks = items.mapNotNull { InnerTubeTrackParser.parseItem(it) }
        val next = InnerTubeShelfParser.continuation(rail)
        return Page(tracks, next?.let { PageToken.Opaque(it) })
    }

    private fun artwork(details: JsonObject): String? =
        details.obj("thumbnail")?.array("thumbnails")?.lastOrNull().str("url")

    private const val UNKNOWN_TITLE = "Unknown title"
    private const val UNKNOWN_ARTIST = "Unknown artist"
}