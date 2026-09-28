package com.naudio.provider.ytmusic

import com.naudio.core.model.Track
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * All YouTube Music (InnerTube) JSON knowledge lives in this file. The rest of
 * Naudio never sees InnerTube structures: everything is parsed into
 * [ParsedSearchPage] / domain values here.
 *
 * Deliberately NOT inspected or processed anywhere in this module:
 * streamingData, signatureCipher, playback URLs, formats — the provider is
 * catalog metadata only.
 */
internal object YtMusicResponseParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Songs filter scope: audio-only + official songs (web client encoding). */
    internal const val SONGS_FILTER_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"

    /** One parsed search page: mapped tracks plus the continuation, if any. */
    internal data class ParsedSearchPage(
        val tracks: List<Track>,
        val continuation: String?,
    )

    /**
     * Parse a search response body into tracks + continuation token.
     * Throws [SerializationException] (mapped to
     * NetworkException.Serialization by the network layer) when the body is
     * not a JSON object or has a structurally invalid shape — a controlled
     * failure, never an empty result.
     */
    internal fun parseSearchResponse(body: String): ParsedSearchPage {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw SerializationException("YTM search response is not a JSON object")
        val contents = root.obj("contents")
            ?: throw SerializationException("YTM search response missing contents object")
        // Real shape: contents.tabbedSearchResultsRenderer.tabs[0].tabRenderer
        // .content.sectionListRenderer (the web client also accepts a bare
        // sectionListRenderer under contents).
        val tabRenderer = contents.obj("tabbedSearchResultsRenderer")
            ?.array("tabs")
            ?.firstOrNull()
            ?.obj("tabRenderer")
        val sectionList = tabRenderer?.obj("content")?.obj("sectionListRenderer")
            ?: contents.obj("sectionListRenderer")
            ?: throw SerializationException("YTM search response missing sectionListRenderer")
        val sections = sectionList.array("contents").orEmpty()

        val tracks = buildList {
            for (section in sections) {
                val shelf = section.obj("musicShelfRenderer") ?: continue
                for (content in shelf.array("contents").orEmpty()) {
                    val renderer = content.obj("musicResponsiveListItemRenderer") ?: continue
                    parseTrack(renderer)?.let { add(it) }
                }
            }
        }
        val continuation = sections.lastOrNull()
            ?.obj("musicShelfRenderer")
            ?.array("continuations")
            ?.firstOrNull()
            ?.obj("nextContinuationData")
            ?.primitive("continuation")
        return ParsedSearchPage(tracks, continuation)
    }

    /**
     * Build the JSON request body for a search. [continuation] is null for the
     * first page; the songs-filter [SONGS_FILTER_PARAMS] field is dropped on
     * continuation pages exactly as the web client does.
     */
    internal fun buildSearchBody(query: String, continuation: String?): String =
        buildJsonObject {
            put("query", query)
            if (continuation == null) {
                put("params", SONGS_FILTER_PARAMS)
            } else {
                put("continuation", continuation)
            }
            put("context", buildContext())
        }.toString()

    /** The public web-client context: identity + default capabilities. */
    private fun buildContext(): JsonObject = buildJsonObject {
        put(
            "client",
            buildJsonObject {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20240403.01.00")
                put("hl", "en")
                put("gl", "US")
            },
        )
    }

    /**
     * Map one musicResponsiveListItemRenderer to a Track, or null when the
     * renderer carries no playable song/video id (artists/albums/playlists
     * are filtered out by the songs-filter params, but defensive mapping
     * keeps the parser total).
     */
    private fun parseTrack(renderer: JsonObject): Track? {
        val flexColumns = renderer.array("flexColumns").orEmpty()
        val videoId = renderer.obj("playlistItemData")?.primitive("videoId")
            ?: flexColumns.firstOrNull()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")
                ?.obj("text")
                ?.array("runs")
                ?.firstOrNull()
                ?.obj("navigationEndpoint")
                ?.obj("watchEndpoint")
                ?.primitive("videoId")
            ?: return null

        val runs = flexColumns.mapNotNull { column ->
            column.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
        }
        val title = runs.getOrNull(0)?.firstOrNull()?.primitive("text").orEmpty()
        val artist = runs.getOrNull(1)?.firstOrNull()?.primitive("text")
        val album = runs.getOrNull(2)?.firstOrNull()?.primitive("text")
        val durationText = runs.lastOrNull()?.lastOrNull()?.primitive("text")

        return Track(
            id = videoId,
            providerId = YtMusicProviderIds.YTMUSIC,
            title = title.ifBlank { "Unknown title" },
            artist = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist",
            album = album?.takeIf { it.isNotBlank() },
            artworkUrl = renderer.extractArtworkUrl(),
            durationMs = durationText?.parseDurationMs() ?: 0L,
        )
    }

    /**
     * Thumbnail url from musicThumbnailRenderer. The web client ships two
     * shapes: `thumbnail` as a direct array of sized entries, or the older
     * nested `thumbnails` array. Largest entry (last) wins; null if absent.
     */
    private fun JsonObject.extractArtworkUrl(): String? {
        val thumbRenderer = obj("thumbnail")?.obj("musicThumbnailRenderer") ?: return null
        val entries = thumbRenderer.array("thumbnail")
            ?: thumbRenderer.obj("thumbnail")?.array("thumbnails")
            ?: return null
        return entries.lastOrNull()?.obj("musicThumbnailRendererDTO_thumbnail")?.primitive("url")
            ?: entries.lastOrNull()?.primitive("url")
    }

    /** "3:45", "1:02:03" -> milliseconds; anything else -> null. */
    internal fun String.parseDurationMs(): Long? {
        val parts = split(':').map { it.trim() }
        if (parts.isEmpty() || parts.any { it.isEmpty() || it.toLongOrNull() == null }) return null
        return parts.fold(0L) { acc, part -> acc * 60 + part.toLong() } * 1000L
    }

    // -- minimal JsonObject traversal helpers -------------------------------

    private fun JsonElement?.obj(name: String): JsonObject? = (this as? JsonObject)?.get(name) as? JsonObject

    private fun JsonElement?.array(name: String): JsonArray? = (this as? JsonObject)?.get(name) as? JsonArray

    private fun JsonObject.primitive(name: String): String? = (get(name) as? JsonPrimitive)?.content

    private fun JsonElement?.primitive(name: String): String? =
        (this as? JsonObject)?.let { (it.get(name) as? JsonPrimitive)?.content }
}
