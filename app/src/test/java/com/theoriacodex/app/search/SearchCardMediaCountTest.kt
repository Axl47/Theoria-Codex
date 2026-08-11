package com.theoriacodex.app.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchCardMediaCountTest {
    @Test
    fun `media badge shows highest watched position over total`() {
        assertEquals("50/742", imageCountBadgeLabel(count = 742, viewedMediaNumber = 50))
    }

    @Test
    fun `ordinary media badge remains a total and progress is clamped`() {
        assertEquals("742", imageCountBadgeLabel(count = 742, viewedMediaNumber = null))
        assertEquals("742/742", imageCountBadgeLabel(count = 742, viewedMediaNumber = 900))
        assertEquals("1/742", imageCountBadgeLabel(count = 742, viewedMediaNumber = 0))
    }
}
