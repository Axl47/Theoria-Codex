package com.theoriacodex.sources.common

import com.google.gson.Gson
import com.theoriacodex.domain.diagnostics.OffMainChecker
import com.theoriacodex.domain.diagnostics.OffMainWork
import org.junit.Assert.assertEquals
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
        // A document parse would move the title into <head> and drop its words from the comment.
        val text = htmlFragmentToText("<title>Spoiler:</title> ending<br>second line")

        assertEquals("Spoiler: ending\nsecond line", text)
    }
}
