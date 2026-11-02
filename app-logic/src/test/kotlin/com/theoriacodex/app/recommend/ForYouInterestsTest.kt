package com.theoriacodex.app.recommend

import com.theoriacodex.app.codex.CodexSaveRelevance
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouInterestsTest {
    @Test fun `separate interests stay together rather than creating unrelated pairs`() {
        val documents = List(4) { listOf("common", "night", "city") } +
            List(4) { listOf("common", "forest", "watercolor") }
        val interests = ForYouInterests.generate(SourceKey.GELBOORU, documents)
        assertEquals(listOf("night", "city"), interests.first().tags)
        assertTrue(interests.any { it.tags == listOf("forest", "watercolor") })
        assertFalse(interests.any { it.tags == listOf("common") })
        assertFalse(interests.any { "night" in it.tags && "forest" in it.tags })
        assertEquals(interests.size, interests.map { it.tags.toSet() }.distinct().size)
    }

    @Test fun `known provider frequency reduces generic anchors without excluding unknown counts`() {
        val documents = List(3) { listOf("broad", "specific") }
        val interests = ForYouInterests.generate(SourceKey.PIXIV, documents,
            mapOf("broad" to 1_000_000, "specific" to 100))
        assertEquals("specific", interests.first().tags.first())
        assertTrue(ForYouInterests.generate(SourceKey.PIXIV, documents).isNotEmpty())
        val partialCounts = ForYouInterests.generate(SourceKey.PIXIV, List(3) { listOf("unknown", "specific", "missing") },
            mapOf("specific" to 100, "missing" to 0))
        assertTrue(partialCounts.none { "missing" in it.tags })
    }

    @Test fun `sections and adjacent exploration are bounded and use source normalization`() {
        val interests = ForYouInterests.generate(SourceKey.GELBOORU,
            (1..12).map { listOf("Interest $it") })
        assertEquals(5, interests.size)
        assertTrue(interests.last().discovery)
        assertTrue(interests.all { it.tags.single().startsWith("interest_") })
        assertEquals(5, interests.flatMap { it.tags }.distinct().size)
        assertEquals(emptyList<ForYouInterest>(), ForYouInterests.generate(SourceKey.PIXIV, listOf(listOf("1000users入り"))))
        assertTrue(CodexSaveRelevance.fromTags(listOf("Blue Hair", "blue_hair")).hasSignals)
    }
}
