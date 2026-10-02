package com.naudio.provider.lrclib

import com.naudio.core.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrcParserTest {

    @Test
    fun `standard timestamp`() {
        val parsed = LrcParser.parse("[01:23.45]Hello")
        assertEquals(listOf(LyricLine(83450, "Hello")), parsed)
    }

    @Test
    fun `milliseconds`() {
        val parsed = LrcParser.parse("[01:23.450]Hello")
        assertEquals(listOf(LyricLine(83450, "Hello")), parsed)
    }

    @Test
    fun `no fractional timestamp`() {
        val parsed = LrcParser.parse("[01:23]Hello")
        assertEquals(listOf(LyricLine(83000, "Hello")), parsed)
    }

    @Test
    fun `multi timestamp`() {
        val parsed = LrcParser.parse("[01:00.00][02:00.00]Chorus")
        assertEquals(
            listOf(
                LyricLine(60000, "Chorus"),
                LyricLine(120000, "Chorus")
            ),
            parsed
        )
    }

    @Test
    fun `malformed timestamp`() {
        // Does not match, should be considered untimed and mixed, yielding null
        val parsed = LrcParser.parse("[01:23.45]Hello\n[invalid:time]Malformed")
        assertNull(parsed)
    }

    @Test
    fun `metadata is skipped`() {
        val parsed = LrcParser.parse("[ti:My Song]\n[01:00.00]Hello")
        assertEquals(listOf(LyricLine(60000, "Hello")), parsed)
    }

    @Test
    fun `empty lines are ignored`() {
        val parsed = LrcParser.parse("\n\n[01:00.00]Hello\n\n")
        assertEquals(listOf(LyricLine(60000, "Hello")), parsed)
    }

    @Test
    fun `sorting`() {
        val parsed = LrcParser.parse("[02:00.00]Two\n[01:00.00]One")
        assertEquals(
            listOf(
                LyricLine(60000, "One"),
                LyricLine(120000, "Two")
            ),
            parsed
        )
    }

    @Test
    fun `whitespace handling`() {
        val parsed = LrcParser.parse("   [01:00.00]   Hello   ")
        assertEquals(listOf(LyricLine(60000, "Hello")), parsed)
    }
}
