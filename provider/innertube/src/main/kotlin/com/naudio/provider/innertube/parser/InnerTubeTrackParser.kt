package com.naudio.provider.innertube.parser

import com.naudio.core.model.Track
import com.naudio.provider.innertube.api.YtMusicBackend
import com.naudio.provider.innertube.parser.InnerTubeJson.array
import com.naudio.provider.innertube.parser.InnerTubeJson.obj
import com.naudio.provider.innertube.parser.InnerTubeJson.parseDurationMs
import com.naudio.provider.innertube.parser.InnerTubeJson.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Maps InnerTube's music list-item renderers onto domain [Track] values.
 *
 * The same track appears in two renderer shapes across the endpoints this
 * backend uses:
 *  - [FULL_RENDERER] — search results, album/playlist/artist shelves. Each
 *    piece of metadata is its own `flexColumn`, so the columns ARE the fields.
 *  - [COMPACT_RENDERER] — the "related" rail on the watch page. Everything is
 *    one flat `text.runs` array whose entries are separated by bullet runs:
 *    `["Title", " • ", "Artist", " • ", "2:34"]`.
 *
 * Both are reduced to the same ordered `fields` list by [splitMetaFields] and
 * then interpreted once, so callers get identical [Track] semantics and there
 * is a single place where the title/artist/album/duration positions are decided.
 *
 * Each field also carries the catalog id the response attached to that text (see
 * [MetaField]), so a track can be reopened in the provider's own artist/album
 * pages. The id is used verbatim: it is read from the response, never derived
 * from a name, and a field the provider left unlinked simply has none.
 *
 * The mapper is total: a renderer without a playable song/video id maps to null
 * (artists, albums and playlists share the list-item shape but carry no
 * playable id, so they are filtered out rather than misreported as tracks).
 *
 * Every emitted [Track] is stamped with [YtMusicBackend.PROVIDER_ID], so a
 * track is always routed back to this backend for playback resolution.
 */
internal object InnerTubeTrackParser {

    private const val FULL_RENDERER = "musicResponsiveListItemRenderer"
    private const val COMPACT_RENDERER = "compactMusicResponsiveListItemRenderer"
    private const val COLUMN_RENDERER = "musicResponsiveListItemFlexColumnRenderer"

    /** The bullet run the web client puts between metadata fields. */
    private const val FIELD_SEPARATOR = "•"

    /**
     * Map every song renderer found anywhere under [root], in document order.
     *
     * [InnerTubeJson.collectByKey] yields renderer VALUES (the object stored
     * under the renderer's key), so they are mapped directly by their known
     * type rather than being pushed back through [parseItem], which expects the
     * enclosing wrapper.
     */
    fun parseAll(root: JsonElement?): List<Track> {
        val full = InnerTubeJson.collectByKey(root, FULL_RENDERER).mapNotNull { mapFull(it) }
        // A response that wraps a full renderer inside a compact one would
        // otherwise emit the track twice.
        val compact = InnerTubeJson.collectByKey(root, COMPACT_RENDERER)
            .filterNot { InnerTubeJson.collectByKey(it, FULL_RENDERER).isNotEmpty() }
            .mapNotNull { mapCompact(it) }
        return full + compact
    }

    /** Map the song renderers of a single list container, in document order. */
    fun parseContents(contents: JsonArray?): List<Track> =
        contents.orEmpty().mapNotNull { parseItem(it) }

    /**
     * Map one list-item element — a full or compact renderer, or a (possibly
     * nested) wrapper containing one of them. Null when the element carries no
     * playable song id.
     */
    fun parseItem(element: JsonElement?, depth: Int = 0): Track? = when (element) {
        null, is JsonPrimitive -> null
        is JsonObject ->
            element.obj(FULL_RENDERER)?.let { mapFull(it) }
                ?: element.obj(COMPACT_RENDERER)?.let { mapCompact(it) }
                ?: if (depth < MAX_WRAPPER_DEPTH) {
                    element.values.firstNotNullOfOrNull { nested -> parseItem(nested, depth + 1) }
                } else {
                    null
                }
        else -> null
    }

    /** Map a `musicResponsiveListItemRenderer`: one field per flex column. */
    private fun mapFull(renderer: JsonObject): Track? {
        val fields = renderer.array("flexColumns").orEmpty().mapNotNull { column ->
            val runs = column.obj(COLUMN_RENDERER)?.obj("text")?.array("runs")
            splitMetaFields(runs).firstOrNull()?.takeIf { it.text.isNotEmpty() }
        }
        return build(renderer, fields)
    }

    /** Map a `compactMusicResponsiveListItemRenderer`: bullet-separated runs. */
    private fun mapCompact(renderer: JsonObject): Track? {
        val fields = splitMetaFields(renderer.obj("text")?.array("runs"))
        return build(renderer, fields)
    }

    /**
     * Reduce a run array to its ordered metadata fields.
     *
     * A column's runs carry no separator (one column == one field), and a
     * compact renderer's runs are separated by bullets, so splitting on the
     * bullet handles both shapes identically and flattens the columns into one
     * ordered list.
     *
     * A run may carry its field's catalog link (`navigationEndpoint.browseEndpoint
     * .browseId`) — the web client hangs it off the very run holding the name, so
     * the id travels with the text it belongs to. Fields assembled from several
     * runs keep the first id seen among them, and a field nothing links has none.
     */
    internal fun splitMetaFields(runs: JsonArray?): List<MetaField> {
        if (runs == null) return emptyList()
        val fields = mutableListOf<MetaField>()
        var text = StringBuilder()
        var browseId: String? = null
        for (run in runs) {
            val runText = run.str("text") ?: continue
            if (runText.trim() == FIELD_SEPARATOR) {
                fields.add(MetaField(text.toString(), browseId))
                text = StringBuilder()
                browseId = null
            } else {
                text.append(runText)
                if (browseId == null) browseId = run.catalogLink()
            }
        }
        fields.add(MetaField(text.toString(), browseId))
        return fields.map { it.copy(text = it.text.trim()) }
    }

    /**
     * Shared tail of both shapes: interpret the ordered fields positionally as
     * title, artist, album, duration — the web client's own layout order.
     *
     * The duration is located by VALUE rather than by position, because a
     * compact rail that lists no album is one field shorter; treating the last
     * field as an album would put "2:34" in the album slot.
     */
    private fun build(renderer: JsonObject, fields: List<MetaField>): Track? {
        val videoId = extractVideoId(renderer) ?: return null
        val durationText = fields.lastOrNull()?.text?.takeIf { parseDurationMs(it) != null }
        val durationIndex = if (durationText == null) -1 else fields.indexOfLast { it.text == durationText }
        val albumField = fields.getOrNull(2)?.takeIf { fields.indexOf(it) != durationIndex }
        val album = albumField?.text?.takeIf { it.isNotEmpty() }
        val artist = fields.getOrNull(1)?.text?.takeIf { it.isNotEmpty() } ?: UNKNOWN_ARTIST
        return Track(
            id = videoId,
            providerId = YtMusicBackend.PROVIDER_ID,
            title = fields.getOrNull(0)?.text?.takeIf { it.isNotEmpty() } ?: UNKNOWN_TITLE,
            artist = artist,
            album = album,
            artworkUrl = extractArtwork(renderer),
            durationMs = parseDurationMs(durationText) ?: 0L,
            artistId = catalogId(fields, artist, albumNamespace = false),
            albumId = catalogId(fields, album, albumNamespace = true),
        )
    }

    /**
     * The catalog id the response linked to the display string [label], or null
     * when it linked none.
     *
     * Identity is read from the RUN, never guessed from a position: the same
     * response puts the artist in one column on one endpoint and inside a shared
     * column on another, so the only trustworthy pairing is the id that sits on
     * the same run as the text. A name is therefore only ever resolved against an
     * id the provider itself sent — it is never used as, or turned into, one.
     *
     * [albumNamespace] picks which namespace counts as a match: the release
     * namespace for an album, anything else for an artist. That is what keeps a
     * release whose title equals its artist's name from handing the artist
     * destination an album id.
     *
     * Null is the ordinary answer — for a provider that links nothing, for a
     * rail the web client renders as plain text, and for a watch page, whose
     * `videoDetails` block is descriptive only. A null id leaves the label plain
     * text in the UI rather than opening a page the provider never named.
     */
    private fun catalogId(fields: List<MetaField>, label: String?, albumNamespace: Boolean): String? {
        if (label.isNullOrEmpty()) return null
        return fields.asSequence()
            .filter { it.text == label }
            .mapNotNull { it.browseId }
            .firstOrNull { it.startsWith(InnerTubeJson.ALBUM_ID_PREFIX) == albumNamespace }
    }

    /** The catalog link a single run carries, or null. Blank is treated as none. */
    private fun JsonElement.catalogLink(): String? =
        obj("navigationEndpoint")
            ?.obj("browseEndpoint")
            ?.str("browseId")
            ?.takeIf { it.isNotBlank() }

    /**
     * The playable song id. The explicit `playlistItemData.videoId` is
     * preferred; the `watchEndpoint` navigation target is the fallback used by
     * shelves that omit it.
     */
    private fun extractVideoId(renderer: JsonObject): String? =
        renderer.obj("playlistItemData")?.str("videoId")
            ?: renderer.obj("navigationEndpoint")?.obj("watchEndpoint")?.str("videoId")
            ?: renderer.obj("overlay")?.obj("musicItemThumbnailOverlayRenderer")
                ?.obj("content")?.obj("musicPlayButtonRenderer")
                ?.str("playNavigationEndpoint")
                ?.let { watchId(it) }
            ?: renderer.obj("onTap")?.obj("watchEndpoint")?.str("videoId")

    /**
     * `watchEndpoint` values occasionally arrive as a `{"watchEndpoint": …}`
     * JSON fragment embedded in a string field. Pulling one literal id out of
     * that fragment is plain field extraction, not deprotection — it carries no
     * signature and no stream URL.
     */
    private fun watchId(raw: String): String? =
        VIDEO_ID_IN_FRAGMENT.find(raw)?.groupValues?.getOrNull(1)

    /**
     * Largest thumbnail the renderer carries. The web client ships two shapes:
     * a direct `thumbnail` array of sized entries, or the older nested
     * `thumbnails` array.
     */
    private fun extractArtwork(renderer: JsonObject): String? {
        val thumb = renderer.obj("thumbnail")?.obj("musicThumbnailRenderer") ?: return null
        val entries = thumb.array("thumbnail")
            ?: thumb.obj("thumbnail")?.array("thumbnails")
            ?: return null
        val last = entries.lastOrNull() ?: return null
        return last.obj("musicThumbnailRendererDTO_thumbnail")?.str("url") ?: last.str("url")
    }

    private val VIDEO_ID_IN_FRAGMENT = Regex("\"videoId\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * One metadata field of a list item: the text to display and, when the
     * response linked it, the provider's own catalog id for that text.
     */
    internal data class MetaField(val text: String, val browseId: String? = null)

/** Bound on wrapper unwrapping, so a pathological payload stays bounded. */
private const val MAX_WRAPPER_DEPTH = 4

    private const val UNKNOWN_TITLE = "Unknown title"
    private const val UNKNOWN_ARTIST = "Unknown artist"
}