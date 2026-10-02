package com.naudio.provider.lrclib

import com.naudio.core.model.LyricLine

/**
 * Deterministic LRC parser (pure Kotlin, no third-party dependency). The UI
 * must never parse raw LRC — providers normalize into [LyricLine] values.
 *
 * Supported syntax:
 *  - timestamps `[mm:ss]`, `[mm:ss.x]`, `[mm:ss.xx]`, `[mm:ss.xxx]`
 *    (fraction separator `.` or `:`; 1 digit = tenths, 2 = hundredths,
 *    3 = milliseconds)
 *  - multiple timestamps per line: `[01:00.00][02:00.00]Chorus` becomes two
 *    [LyricLine]s
 *  - metadata tags `[ar:..] [ti:..] [al:..] [by:..] ...` are skipped
 *  - the `[offset:±ms]` tag shifts every timestamp (clamped to
 *    ±[MAX_OFFSET_MS]; resulting times clamped to >= 0)
 *
 * Rejection contract — `parse` returns `null` (the caller must NOT present
 * the result as synchronized lyrics) when the input:
 *  - exceeds [MAX_INPUT_CHARS] or [MAX_LINES] (payload protection)
 *  - contains no valid timestamp at all
 *  - mixes timed lines with untimed content lines (a lone stray tag or a
 *    trailing untimed line must not silently become Synced lyrics; callers
 *    degrade to plain lyrics instead)
 * Empty-text timed lines (gap markers such as `[00:41.00]` alone) are
 * dropped: they carry no display value and the highlight correctly jumps to
 * the next non-empty line.
 */
internal object LrcParser {

    /** Maximum accepted raw LRC payload (256 KiB). Larger inputs are rejected. */
    const val MAX_INPUT_CHARS = 262_144

    /** Maximum number of non-empty lines. Larger inputs are rejected. */
    const val MAX_LINES = 2_000

    /** Absolute clamp for the `[offset:..]` tag. */
    const val MAX_OFFSET_MS = 60_000L

    /** `[mm:ss]`, `[mm:ss.x]`, `[mm:ss.xx]`, `[mm:ss.xxx]` (fraction `.` or `:`). */
    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** Whole-line metadata tag such as `[ar:Artist]` or `[length:03:45]`. */
    private val METADATA_TAG = Regex("""^\[[A-Za-z][A-Za-z0-9]*:.*]$""")

    /** Whole-line `[offset:±ms]` tag. */
    private val OFFSET_TAG = Regex("""^\[offset:([+-]?\d+)]$""", RegexOption.IGNORE_CASE)

    /**
     * Parse raw LRC into time-ascending [LyricLine]s, or `null` when the
     * input must not be presented as synchronized lyrics (see rejection
     * contract above).
     */
    fun parse(raw: String): List<LyricLine>? {
        if (raw.length > MAX_INPUT_CHARS) return null
        var offsetMs = 0L
        var processedLines = 0
        val timed = ArrayList<LyricLine>(64)
        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            processedLines++
            if (processedLines > MAX_LINES) return null

            val leading = leadingTimestamps(line)
            if (leading == null) {
                val offsetMatch = OFFSET_TAG.matchEntire(line)
                when {
                    offsetMatch != null ->
                        offsetMs = offsetMatch.groupValues[1].toLongOrNull()
                            ?.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
                            ?: 0L

                    METADATA_TAG.matchEntire(line) != null -> Unit // metadata: skip

                    // Untimed content line mixed with timed ones: reject the
                    // whole payload as synchronized lyrics (degrade to plain).
                    else -> return null
                }
                continue
            }
            val text = line.substring(leading.endIndex).trim()
            if (text.isEmpty()) continue // timed gap marker — nothing to display
            for (startMs in leading.startTimesMs) {
                timed.add(LyricLine((startMs + offsetMs).coerceAtLeast(0L), text))
            }
        }
        if (timed.isEmpty()) return null
        timed.sortBy { it.startTimeMs } // stable: duplicate timestamps keep input order
        return timed
    }

    /**
     * Consecutive timestamps from the start of [line] (whitespace allowed
     * between tags). Returns `null` when the line does not begin with a
     * timestamp; otherwise the parsed start times (milliseconds, before any
     * offset) and the index just past the last timestamp.
     */
    private fun leadingTimestamps(line: String): LeadingTimestamps? {
        var index = 0
        val startTimes = ArrayList<Long>(2)
        while (true) {
            while (index < line.length && line[index].isWhitespace()) index++
            val match = TIMESTAMP.matchAt(line, index) ?: break
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val fractionRaw = match.groupValues[3]
            val fractionMs = when (fractionRaw.length) {
                0 -> 0L
                1 -> fractionRaw.toLong() * 100
                2 -> fractionRaw.toLong() * 10
                else -> fractionRaw.toLong()
            }
            startTimes.add((minutes * 60 + seconds) * 1000 + fractionMs)
            index = match.range.last + 1
        }
        if (startTimes.isEmpty()) return null
        return LeadingTimestamps(startTimes, index)
    }

    private data class LeadingTimestamps(
        val startTimesMs: List<Long>,
        val endIndex: Int,
    )
}
