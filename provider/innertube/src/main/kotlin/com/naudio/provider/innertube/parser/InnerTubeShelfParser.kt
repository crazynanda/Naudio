package com.naudio.provider.innertube.parser

import com.naudio.core.model.Track
import com.naudio.provider.innertube.parser.InnerTubeJson.array
import com.naudio.provider.innertube.parser.InnerTubeJson.firstObj
import com.naudio.provider.innertube.parser.InnerTubeJson.obj
import com.naudio.provider.innertube.parser.InnerTubeJson.str
import com.naudio.provider.innertube.parser.InnerTubeJson.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One titled group of tracks lifted out of an InnerTube response.
 *
 * Deliberately a parser-internal value: by the time a shelf reaches a caller it
 * has already been folded into a domain type ([com.naudio.provider.innertube.api.YtMusicHomeSection],
 * the `tracks` list of an artist/album/playlist, ...).
 */
internal data class InnerTubeShelf(
    val title: String?,
    val tracks: List<Track>,
    val continuation: String?,
    /**
     * The underlying renderer. Kept so a caller that also needs non-track
     * cards from the same shelf (e.g. an artist's release cards) can look at
     * the raw subtree without the shelf having to model it. Never leaves the
     * backend.
     */
    val raw: JsonObject? = null,
)

/**
 * Finds shelves and continuation cursors in browse-shaped InnerTube responses.
 *
 * InnerTube moves the same data between `singleColumnBrowseResultsRenderer`,
 * `twoColumnBrowseResultsRenderer` and their `sectionListRenderer`s depending on
 * the surface and the experiment bucket, so shelves are located structurally
 * rather than by spelling out every absolute path.
 */
internal object InnerTubeShelfParser {

    private const val MUSIC_SHELF = "musicShelfRenderer"
    private const val PLAYLIST_SHELF = "musicPlaylistShelfRenderer"

    /** Every music shelf under [root], in document order. */
    fun parseShelves(root: JsonElement?): List<InnerTubeShelf> =
        InnerTubeJson.collectByKey(root, MUSIC_SHELF).map { shelf(it) } +
            InnerTubeJson.collectByKey(root, PLAYLIST_SHELF).map { shelf(it) }

    /** The single shelf under [root], or null when the response carries none. */
    fun parseOnlyShelf(root: JsonElement?): InnerTubeShelf? = parseShelves(root).firstOrNull()

    private fun shelf(renderer: JsonObject): InnerTubeShelf = InnerTubeShelf(
        raw = renderer,
        title = title(renderer),
        tracks = InnerTubeTrackParser.parseContents(
            renderer.array("contents")
                ?: renderer.obj("musicPlaylistShelfContentRenderer")?.array("contents"),
        ),
        continuation = continuation(renderer),
    )

    /**
     * A shelf heading. The web client has used both `title.text.runs[0].text`
     * and a bare `title.runs[0].text`; both are accepted.
     */
    private fun title(renderer: JsonObject): String? =
        renderer.obj("title")?.let { heading ->
            heading.obj("text")?.firstObj("runs")?.str("text")
                ?: heading.str("text")
                ?: heading.firstObj("runs")?.str("text")
        }
            ?: renderer.str("headerText")

    /**
     * The cursor for the next page of this shelf. InnerTube nests it either
     * under `continuations[]` or directly on the renderer as
     * `continuations`/`reloadContinuationData`; both shapes are accepted, and
     * the section-level cursor is used as a fallback by the callers.
     */
    fun continuation(root: JsonElement?): String? {
        val data = InnerTubeJson.findByKey(root, "nextContinuationData")
            ?: InnerTubeJson.findByKey(root, "reloadContinuationData")
        return data?.str("continuation")
    }
}