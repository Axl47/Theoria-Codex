package com.theoriacodex.sources.common

import com.google.gson.Gson
import com.theoriacodex.domain.diagnostics.OffMainChecker
import com.theoriacodex.domain.diagnostics.OffMainWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceParsingTest {
    @Test
    fun `every parse helper marks its work as background only`() {
        val labels = mutableListOf<String>()
        val previous = OffMainWork.install(OffMainChecker { label -> labels += label })
        try {
            parseJsonArray("[]", Gson(), "test")
            parseJsonObject("{}", Gson(), "test")
            parseJsonElement("1", Gson(), "test")
            parseHtml("<p>a</p>")
            parseXml("<a/>")
            parseHtmlFragment("<p>a</p>")
        } finally {
            OffMainWork.install(previous)
        }

        assertEquals(List(6) { OffMainWork.SOURCE_PARSE }, labels)
    }

    @Test
    fun `comment markup is read as a body fragment`() {
        val text = htmlFragmentToText("<title>Spoiler:</title> ending<br>second line")

        assertEquals("Spoiler: ending\nsecond line", text)
    }

    @Test
    fun `a timestamp outside the epoch-millisecond range reads as no date`() {
        assertEquals(1_763_642_096_000L, parseOffsetDateTimeMs("2025-11-20T12:34:56Z"))
        assertNull(parseOffsetDateTimeMs("+300000000-01-01T00:00:00Z"))
    }
}
