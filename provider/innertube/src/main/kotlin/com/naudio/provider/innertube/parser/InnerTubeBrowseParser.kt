package com.naudio.provider.innertube.parser

import com.naudio.provider.innertube.api.YtMusicAlbum
import com.naudio.provider.innertube.api.YtMusicArtist
import com.naudio.provider.innertube.api.YtMusicHome
import com.naudio.provider.innertube.api.YtMusicHomeSection
import com.naudio.provider.innertube.api.YtMusicPlaylist
import com.naudio.provider.innertube.parser.InnerTubeJson.array
import com.naudio.provider.innertube.parser.InnerTubeJson.firstObj
import com.naudio.provider.innertube.parser.InnerTubeJson.obj
import com.naudio.provider.innertube.parser.InnerTubeJson.str
import kotlinx.serialization.json.JsonObject

/**
 * Parses InnerTube *browse* responses — channels, albums, playlists and the
 * home feed — into the domain catalog types that cross the
 * [com.naudio.provider.innertube.api.YtMusicBackend] boundary.
 *
 * Browse responses vary far more than search does: the header lives in one of
 * three renderers depending on the surface, the body may be single- or
 * two-column, and the interesting shelf may be several levels down. Everything
 * here is therefore located structurally and defensively. A missing header or
 * missing shelf degrades to an empty value on that field only — it never fails
 * the whole parse, because "this release has no second page" and "this page has
 * no artwork" are normal, not malformed.
 */
internal object InnerTubeBrowseParser {

    /** Header renderers that may carry a page's identity, in priority order. */
    private val HEADER_RENDERERS = listOf(
        "musicResponsiveHeaderRenderer",
        "musicDetailHeaderRenderer",
        "musicVisualHeaderRenderer",
    )

    /** Two-row (album / single) item renderer, used for an artist's releases. */
    private const val TWO_ROW_ITEM = "musicTwoRowItemRenderer"

    // ------------------------------------------------------------------
    // Home
    // ------------------------------------------------------------------

    fun parseHome(body: String): YtMusicHome =
        YtMusicHome(InnerTubeShelfParser.parseShelves(InnerTubeJson.parseObject(body)).map { shelf ->
            YtMusicHomeSection(title = shelf.title, tracks = shelf.tracks)
        })

    // ------------------------------------------------------------------
    // Artist
    // ------------------------------------------------------------------

    fun parseArtist(browseId: String, body: String): YtMusicArtist {
        val root = InnerTubeJson.parseObject(body)
        val header = findHeader(root)
        val shelves = InnerTubeShelfParser.parseShelves(root)
        return YtMusicArtist(
            id = browseId,
            name = headerTitle(header) ?: UNKNOWN_NAME,
            description = headerDescription(header),
            artworkUrl = headerArtwork(header),
            tracks = shelves.firstOrNull { it.title?.equals(SONGS_SHELF, ignoreCase = true) == true }
                ?.tracks
                ?: shelves.flatMap { it.tracks },
            albums = shelves.flatMap { parseReleaseShelf(it) },
            continuation = InnerTubeShelfParser.continuation(root),
        )
    }

    // ------------------------------------------------------------------
    // Album
    // ------------------------------------------------------------------

    fun parseAlbum(browseId: String, body: String): YtMusicAlbum {
        val root = InnerTubeJson.parseObject(body)
        val header = findHeader(root)
        val shelf = InnerTubeShelfParser.parseOnlyShelf(root)
        return YtMusicAlbum(
            id = browseId,
            title = headerTitle(header) ?: UNKNOWN_NAME,
            artist = descriptor(header).firstOrNull(),
            artworkUrl = headerArtwork(header),
            year = releaseYear(header),
            tracks = shelf?.tracks.orEmpty(),
            continuation = shelf?.continuation ?: InnerTubeShelfParser.continuation(root),
        )
    }

    // ------------------------------------------------------------------
    // Playlist
    // ------------------------------------------------------------------

    fun parsePlaylist(browseId: String, body: String): YtMusicPlaylist {
        val root = InnerTubeJson.parseObject(body)
        val header = findHeader(root)
        val shelf = InnerTubeShelfParser.parseOnlyShelf(root)
        return YtMusicPlaylist(
            id = browseId,
            title = headerTitle(header) ?: UNKNOWN_NAME,
            author = descriptor(header).firstOrNull(),
            artworkUrl = headerArtwork(header),
            // The count ships with a unit suffix ("42 songs"), so the leading integer is
            // taken rather than requiring the whole field to be numeric.
            trackCount = headerSubtitle(header)
                ?.mapNotNull { it.trim().substringBefore(' ').toIntOrNull() }
                ?.firstOrNull { it > 0 },
            tracks = shelf?.tracks.orEmpty(),
            continuation = shelf?.continuation ?: InnerTubeShelfParser.continuation(root),
        )
    }

    // ------------------------------------------------------------------
    // Shared header / release helpers
    // ------------------------------------------------------------------

    private fun findHeader(root: JsonObject?): JsonObject? {
        for (name in HEADER_RENDERERS) {
            InnerTubeJson.findByKey(root, name)?.let { return it }
        }
        return null
    }

    private fun headerTitle(header: JsonObject?): String? =
        header?.obj("title")?.let { it.str("text") ?: it.firstObj("runs")?.str("text") }

    /**
     * The flattened `subtitle` lines, e.g. `["Album", "Rick Astley", "1989"]`.
     *
     * The runs are joined FIRST and split on the bullet afterwards: the web
     * client emits each field as its own run interleaved with " • " runs, so
     * splitting per-run would yield stray bullets and fragments.
     */
    private fun headerSubtitle(header: JsonObject?): List<String>? {
        val runs = header?.obj("subtitle")?.array("runs") ?: return null
        val joined = runs.mapNotNull { it.str("text") }.joinToString("")
        return joined.split('•').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * The subtitle fields that actually describe WHO/WHAT: the leading type
     * label ("Album", "Playlist", …) and the release year are stripped.
     *
     * The web client's subtitle is positional — type, then name, then year or
     * count — so "the second field" would be wrong the moment a header omits
     * the type. Filtering by meaning is robust to that.
     */
    private fun descriptor(header: JsonObject?): List<String> =
        headerSubtitle(header)
            ?.filterNot { it in TYPE_LABELS }
            ?.filterNot { it.length == 4 && it.all(Char::isDigit) }
            ?: emptyList()

    private fun releaseYear(header: JsonObject?): String? =
        headerSubtitle(header)?.firstOrNull { it.length == 4 && it.all(Char::isDigit) }

    /**
     * The description block, preferring the explicit strapline and falling back
     * to the accessibility text of the second subtitle line.
     */
    private fun headerDescription(header: JsonObject?): String? =
        header?.obj("straplineTextOne")?.str("text")
            ?: header?.obj("description")?.firstObj("runs")?.str("text")
            ?: header?.obj("straplineTextTwo")?.obj("runs")?.firstObj("text")?.str("content")

    private fun headerArtwork(header: JsonObject?): String? {
        val thumb = header?.obj("thumbnail") ?: return null
        val renderer = thumb.obj("croppedSquareThumbnailRenderer")
            ?: thumb.obj("musicThumbnailRenderer")
            ?: return null
        return largestThumbnail(renderer)
    }

    /**
     * The largest URL in a thumbnail renderer. Both shipped shapes are accepted:
     * a direct `thumbnail` array of sized entries, or the older nested
     * `thumbnails` array under `thumbnail`.
     */
    private fun largestThumbnail(renderer: JsonObject): String? {
        val entries = renderer.array("thumbnail")
            ?: renderer.obj("thumbnail")?.array("thumbnails")
            ?: return null
        val last = entries.lastOrNull() ?: return null
        return last.obj("musicThumbnailRendererDTO_thumbnail")?.str("url") ?: last.str("url")
    }

    /**
     * Release summaries (`musicTwoRowItemRenderer` cards) inside one shelf.
     * These are albums, not playable tracks, so they never enter the track list.
     */
    private fun parseReleaseShelf(shelf: InnerTubeShelf): List<YtMusicAlbum> =
        InnerTubeJson.collectByKey(shelf.raw, TWO_ROW_ITEM).mapNotNull { card ->
            val browseId = card.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId")
                ?: return@mapNotNull null
            val artwork = card.obj("thumbnailRenderer")?.obj("musicThumbnailRenderer")
                ?.let { largestThumbnail(it) }
            YtMusicAlbum(
                id = browseId,
                title = card.obj("title")?.firstObj("runs")?.str("text") ?: UNKNOWN_NAME,
                artist = card.obj("subtitle")?.firstObj("runs")?.str("text"),
                artworkUrl = artwork,
            )
        }

    private const val SONGS_SHELF = "Songs"
    private const val UNKNOWN_NAME = "Unknown"

    /** Leading subtitle labels that describe the release TYPE, not its author. */
    private val TYPE_LABELS = setOf("Album", "Single", "EP", "Playlist", "Song", "Video")
}