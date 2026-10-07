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
import kotlinx.serialization.json.JsonElement
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

    /**
     * Header renderers that may carry a page's identity, in priority order.
     *
     * `musicImmersiveHeaderRenderer` is the ARTIST page's header and is listed
     * first: when it is the only header present it must win, and it cannot
     * shadow an album or playlist header because those surfaces ship no
     * immersive header at all. Leaving it out (as this parser once did) is not a
     * degradation but a total loss of identity — a page whose header went
     * unread rendered every artist as "Unknown" with placeholder artwork while
     * its songs still loaded, because songs come from a different subtree.
     */
    private val HEADER_RENDERERS = listOf(
        "musicImmersiveHeaderRenderer",
        "musicResponsiveHeaderRenderer",
        "musicDetailHeaderRenderer",
        "musicVisualHeaderRenderer",
    )

    /** Two-row (album / single) item renderer, used for an artist's releases. */
    private const val TWO_ROW_ITEM = "musicTwoRowItemRenderer"

    /** An artist page's bio block, which no longer lives in the header. */
    private const val DESCRIPTION_SHELF = "musicDescriptionShelfRenderer"

    /**
     * The page types a link may declare for itself that mean "this is a release".
     *
     * Read from the response, never inferred: an artist page's release carousels
     * also hold songs, videos, playlists and OTHER ARTISTS as two-row cards, and
     * only the declared page type says which is which. A related artist must not
     * be reported as an album with the artist's name as its title.
     */
    private val RELEASE_PAGE_TYPES = setOf(
        "MUSIC_PAGE_TYPE_ALBUM",
        "MUSIC_PAGE_TYPE_AUDIOBOOK",
    )

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
            // The header carries the strapline/subscriber line; the artist's bio
            // lives in its own block below the header, so either may hold it.
            description = headerDescription(header) ?: descriptionShelf(root),
            artworkUrl = headerArtwork(header),
            tracks = shelves.firstOrNull { it.title?.equals(SONGS_SHELF, ignoreCase = true) == true }
                ?.tracks
                ?: shelves.flatMap { it.tracks },
            albums = parseReleases(root),
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

    /**
     * The artist's bio from its own description block.
     *
     * Artist pages ship it under the header rather than inside it, so reading the
     * header alone reports no bio at all. A missing block is not a failure: the
     * bio is optional, and nothing here substitutes a placeholder for it.
     */
    private fun descriptionShelf(root: JsonElement?): String? =
        InnerTubeJson.findByKey(root, DESCRIPTION_SHELF)
            ?.obj("description")
            ?.firstObj("runs")
            ?.str("text")

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
     * Release summaries (`musicTwoRowItemRenderer` cards) anywhere under [root].
     *
     * These are albums and singles, not playable tracks, so they never enter the
     * track list.
     *
     * The whole response is searched rather than one named shelf, because the
     * web client ships an artist's releases as CAROUSELS
     * (`musicCarouselShelfRenderer`, "Albums" / "Singles" / "Shows"), which are
     * not music shelves at all. Searching shelves for these cards therefore finds
     * nothing, and the discography silently disappears.
     *
     * Which two-row cards are releases is decided by the response, never by
     * title: a card counts only when its own link says so. An artist page's
     * carousels also hold songs, videos, playlists and related artists as
     * two-row cards, and reporting a related artist as an album would put a
     * channel id on the album destination.
     */
    private fun parseReleases(root: JsonElement?): List<YtMusicAlbum> =
        InnerTubeJson.collectByKey(root, TWO_ROW_ITEM).mapNotNull { card ->
            val link = card.releaseLink()
            val browseId = link?.str("browseId")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (!link.isReleaseLink(browseId)) return@mapNotNull null
            val artwork = card.obj("thumbnailRenderer")?.obj("musicThumbnailRenderer")
                ?.let { largestThumbnail(it) }
            val subtitles = card.obj("subtitle")?.array("runs").orEmpty()
            YtMusicAlbum(
                id = browseId,
                title = card.obj("title")?.firstObj("runs")?.str("text") ?: UNKNOWN_NAME,
                // The artist is whichever subtitle run the response links; the
                // leading run is the release TYPE or its year, not the performer.
                artist = subtitles.firstNotNullOfOrNull { run ->
                    run.str("text")?.takeIf { run.obj("navigationEndpoint") != null }
                },
                artworkUrl = artwork,
                year = subtitles.firstNotNullOfOrNull { it.str("text")?.takeIf(::isYear) },
            )
        }

    /**
     * The catalog link a release card carries.
     *
     * Current responses link the card from its TITLE run; older ones from the
     * card itself. Both are accepted, and only both — a card with neither has no
     * identity to open and is skipped rather than guessed at.
     */
    private fun JsonObject.releaseLink(): JsonObject? =
        obj("title")?.firstObj("runs")?.obj("navigationEndpoint")?.obj("browseEndpoint")
            ?: obj("navigationEndpoint")?.obj("browseEndpoint")

    /**
     * True when [link] identifies a release.
     *
     * A declared page type is believed outright. When the response declares
     * none, only an id in the release namespace is accepted — an undeclared link
     * of any other kind is not presumed to be an album.
     */
    private fun JsonObject.isReleaseLink(browseId: String): Boolean =
        when (val pageType = declaredPageType()) {
            null -> browseId.startsWith(InnerTubeJson.ALBUM_ID_PREFIX)
            else -> pageType in RELEASE_PAGE_TYPES
        }

    /** The page type this link declares for itself, or null when it declares none. */
    private fun JsonObject.declaredPageType(): String? =
        obj("browseEndpointContextSupportedConfigs")
            ?.obj("browseEndpointContextMusicConfig")
            ?.str("pageType")

    /** A four-digit release year, kept as text because that is what the UI shows. */
    private fun isYear(text: String): Boolean = text.length == 4 && text.all(Char::isDigit)

    private const val SONGS_SHELF = "Songs"
    private const val UNKNOWN_NAME = "Unknown"

    /** Leading subtitle labels that describe the release TYPE, not its author. */
    private val TYPE_LABELS = setOf("Album", "Single", "EP", "Playlist", "Song", "Video")
}