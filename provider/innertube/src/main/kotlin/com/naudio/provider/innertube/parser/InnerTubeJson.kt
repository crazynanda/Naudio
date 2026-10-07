package com.naudio.provider.innertube.parser

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Minimal, total JSON-tree traversal helpers shared by the InnerTube parsers.
 *
 * InnerTube responses are deeply nested and inconsistently shaped: the same
 * concept appears under different parents depending on the endpoint, the
 * desktop layout and A/B experiments. Spelling out every absolute path would
 * make the parsers brittle, so these helpers are total — every accessor returns
 * null (or an empty list) instead of throwing — and structural errors are
 * reported explicitly by each parser where they matter.
 */
internal object InnerTubeJson {

    /** Lenient instance: InnerTube adds fields constantly and ships loose JSON. */
    val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parse [body] as a JSON object, or null when it is not one OR is not valid
     * JSON at all. Total by design: a caller that already has its own
     * structural contract (see [com.naudio.provider.innertube.parser.InnerTubeSearchParser])
     * parses strictly instead; a caller that only wants "whatever the browse
     * page held" must not blow up on an empty or corrupt body.
     */
    fun parseObject(body: String): JsonObject? = try {
        json.parseToJsonElement(body) as? JsonObject
    } catch (invalid: SerializationException) {
        null
    }

    /** The object at [name], or null. */
    fun JsonElement?.obj(name: String): JsonObject? = (this as? JsonObject)?.get(name) as? JsonObject

    /** The array at [name], or null. */
    fun JsonElement?.array(name: String): JsonArray? = (this as? JsonObject)?.get(name) as? JsonArray

    /** The string value at [name], or null. */
    fun JsonObject?.str(name: String): String? = (this?.get(name) as? JsonPrimitive)?.content

    /**
     * The string value at [name] of any element, or null when the element is
     * not an object. Lets call sites chain straight off array elements
     * (`array.lastOrNull().str("url")`) without an explicit cast at every site;
     * when the static type is already [JsonObject] the overload above wins, and
     * both are identical in behaviour.
     */
    fun JsonElement?.str(name: String): String? = (this as? JsonObject)?.str(name)

    /** The FIRST ELEMENT of the array at [name], or null when absent/empty. */
    fun JsonElement?.firstOf(name: String): JsonElement? = array(name)?.firstOrNull()

    /** The first element of the array at [name], when it is an object. */
    fun JsonElement?.firstObj(name: String): JsonObject? = firstOf(name) as? JsonObject

    /**
     * Depth-first walk collecting every object in this subtree that has a
     * child object under [key]. Order is document order, so callers keep the
     * backend's own ordering.
     *
     * [maxDepth] bounds the walk: real InnerTube payloads are shallow enough
     * that a small bound is ample, and the bound makes a pathological or
     * hostile payload a bounded cost rather than an unbounded traversal.
     */
    fun collectByKey(root: JsonElement?, key: String, maxDepth: Int = 12): List<JsonObject> {
        if (root == null || maxDepth < 0) return emptyList()
        val found = mutableListOf<JsonObject>()
        fun walk(node: JsonElement, depth: Int) {
            if (depth > maxDepth) return
            when (node) {
                is JsonObject -> {
                    node[key]?.let { child ->
                        if (child is JsonObject) found.add(child)
                    }
                    node.values.forEach { walk(it, depth + 1) }
                }
                is JsonArray -> node.forEach { walk(it, depth + 1) }
                else -> Unit
            }
        }
        walk(root, 0)
        return found
    }

    /** The first object in the subtree carrying a child object under [key]. */
    fun findByKey(root: JsonElement?, key: String, maxDepth: Int = 12): JsonObject? =
        collectByKey(root, key, maxDepth).firstOrNull()

    /**
     * The browse namespace that identifies a RELEASE (an album / single / EP) as
     * opposed to the channel a track is performed by.
     *
     * InnerTube knowledge, so it stays in the backend: callers across the
     * boundary receive an opaque id and nothing about its shape. Used only to
     * tell two ids the response already supplied apart — never to invent one.
     */
    const val ALBUM_ID_PREFIX = "MPRE"

    /**
     * `"3:45"` / `"1:02:03"` -> milliseconds; anything else (including a blank
     * string) -> null.
     */
    fun parseDurationMs(text: String?): Long? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(':').map { it.trim() }
        if (parts.any { it.isEmpty() || it.toLongOrNull() == null }) return null
        return parts.fold(0L) { acc, part -> acc * 60 + part.toLong() } * 1000L
    }
}
