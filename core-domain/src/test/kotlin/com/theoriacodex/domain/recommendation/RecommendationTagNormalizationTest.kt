package com.theoriacodex.domain.recommendation

import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecommendationTagNormalizationTest {
    @Test
    fun `booru family tags use one lowercase underscore key`() {
        val raw = "  -Cloud   City  "

        listOf(
            SourceKey.GELBOORU,
            SourceKey.AIBOORU,
            SourceKey.IWARA,
            SourceKey.RULE34XXX,
        ).forEach { source ->
            assertEquals("cloud_city", RecommendationTagNormalization.normalize(source, raw))
        }
    }

    @Test
    fun `native-space sources keep canonical spaces`() {
        val raw = "  Cloud   City  "

        listOf(
            SourceKey.PIXIV,
            SourceKey.NHENTAI,
            SourceKey.HITOMI,
            SourceKey.RULE34PAHEAL,
            SourceKey.RULE34VIDEO,
            SourceKey.RULE34GEN,
        ).forEach { source ->
            assertEquals("cloud city", RecommendationTagNormalization.normalize(source, raw))
        }
    }

    @Test
    fun `pixiv popularity markers never enter affinity keys`() {
        assertNull(RecommendationTagNormalization.normalize(SourceKey.PIXIV, "100users入り"))
        assertNull(RecommendationTagNormalization.normalize(SourceKey.PIXIV, "100users入り anniversary"))
        assertEquals("landscape", RecommendationTagNormalization.normalize(SourceKey.PIXIV, "Landscape"))
    }

    @Test
    fun `normalizes distinct fixtures in stable input order`() {
        assertEquals(
            listOf("cloud_city", "sunset"),
            RecommendationTagNormalization.normalizeDistinct(
                source = SourceKey.GELBOORU,
                rawTags = listOf("Cloud City", " cloud_city ", " ", "Sunset", "ignored"),
                limit = 2,
            ),
        )
    }
}
