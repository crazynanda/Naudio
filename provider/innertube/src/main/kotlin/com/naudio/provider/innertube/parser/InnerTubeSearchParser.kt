package com.naudio.provider.innertube.parser

import com.naudio.core.model.Track
import com.naudio.provider.api.Page
import com.naudio.provider.api.PageToken
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/**
 * Parses a songs-filtered InnerTube search response into one page of domain
 * tracks plus the opaque cursor for the next page.
 *
 * Structural failure is reported, never hidden: a body that is not a JSON
 * object, or that carries no search-result container at all, raises
 * [SerializationException], which the shared network layer maps to
 * `NetworkException.Serialization`. An empty result set is NOT a failure — it is
 * an empty [Page] with a null cursor, exactly like every other provider in the
 * project.
 */
internal object InnerTubeSearchParser {

    /** Containers that hold search results, newest layout first. */
    private val RESULT_CONTAINERS = listOf("tabbedSearchResultsRenderer", "sectionListRenderer")

    fun parse(body: String): Page<Track> {
        // Parsed strictly (not via the total InnerTubeJson.parseObject) so a
        // corrupt body raises a real SerializationException rather than being
        // reported as "no results".
        val root = InnerTubeJson.json.parseToJsonElement(body) as? JsonObject
            ?: throw SerializationException("YT Music search response is not a JSON object")
        val container = requireContainer(root)
        val tracks = InnerTubeTrackParser.parseAll(container)
        val next = InnerTubeShelfParser.continuation(container)
        return Page(tracks, next?.let { PageToken.Opaque(it) })
    }

    /**
     * The element actually holding the result sections. The web client wraps
     * search results in `contents.tabbedSearchResultsRenderer.tabs[0]
     * .tabRenderer.content`, and also accepts a bare `sectionListRenderer`
     * directly under `contents`.
     */
    private fun requireContainer(root: JsonObject): JsonObject {
        val contents = root["contents"] as? JsonObject
            ?: throw SerializationException("YT Music search response missing contents object")
        for (name in RESULT_CONTAINERS) {
            val found = InnerTubeJson.collectByKey(contents, name).firstOrNull()
            if (found != null) return found
        }
        throw SerializationException("YT Music search response missing search results container")
    }
}