package com.theoriacodex.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStreamTest {
    @Test
    fun `playable streams have an address and a label of their own`() {
        val streams = listOf(
            MediaStream("https://a.example/1", "1080p"),
            MediaStream(" ", "720p"),
            MediaStream("https://b.example/1", "1080p"),
            MediaStream("https://c.example/1", "  "),
            MediaStream("https://d.example/1", "1080p "),
        )

        assertEquals(listOf("1080p", "1080p (2)", "Stream 3", "1080p (3)"), streams.playable().map { it.label })
        assertEquals(listOf("https://a.example/1", "https://b.example/1", "https://c.example/1", "https://d.example/1"), streams.playable().map { it.url })
    }
}
