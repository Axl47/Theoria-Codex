package com.theoriacodex.domain.tags

import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTagNormalizationTest {
    @Test
    fun `hitomi tags match across provider underscores and display spaces`() {
        assertEquals("full color", sourceTagKey(SourceKey.HITOMI, " Full_Color "))
        assertTrue(sourceTagsMatch(SourceKey.HITOMI, "full_color", "Full Color"))
    }
}
