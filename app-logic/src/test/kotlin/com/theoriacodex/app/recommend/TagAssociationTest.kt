package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.recommendation.RecommendationTagNormalization
import org.junit.Assert.assertEquals
import org.junit.Test

class TagAssociationTest {
    @Test
    fun `affinity training uses the domain recommendation normalization fixture`() {
        val fixture = listOf(
            "  -Cloud   City  ",
            "cloud_city",
            "Sunset",
            " ",
        )
        val expectedKeys = RecommendationTagNormalization
            .normalizeDistinct(SourceKey.GELBOORU, fixture)
            .toSet()

        val stats = buildSourceTagAffinity(
            documentsBySource = mapOf(SourceKey.GELBOORU to listOf(fixture)),
        ).getValue(SourceKey.GELBOORU)

        assertEquals(expectedKeys, stats.tagDocumentCounts.keys)
    }

    @Test
    fun `affinity training excludes pixiv popularity markers through domain policy`() {
        val stats = buildSourceTagAffinity(
            documentsBySource = mapOf(
                SourceKey.PIXIV to listOf(listOf("100users入り", "Landscape")),
            ),
        ).getValue(SourceKey.PIXIV)

        assertEquals(setOf("landscape"), stats.tagDocumentCounts.keys)
    }
}
