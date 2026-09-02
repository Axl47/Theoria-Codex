package com.theoriacodex.app.viewer

import com.theoriacodex.data.repository.ViewerOcrLanguage
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerOcrPolicyTest {
    @Test
    fun `typed metadata leads ready enabled fallback without duplicates`() {
        val metadata = metadataOcrLanguage(
            listOf(
                PostTaxonomyTerm("chinese dress", SearchFacet.TAG),
                PostTaxonomyTerm("zh-Hant", SearchFacet.LANGUAGE, "language"),
            ),
        )

        val order = orderedOcrLanguages(
            metadataLanguage = metadata,
            enabledLanguages = ViewerOcrLanguage.entries.toSet(),
            readyLanguages = ViewerOcrLanguage.entries.toSet(),
        )

        assertEquals(ViewerOcrLanguage.CHINESE, metadata)
        assertEquals(
            listOf(
                ViewerOcrLanguage.CHINESE,
                ViewerOcrLanguage.JAPANESE,
                ViewerOcrLanguage.KOREAN,
            ),
            order,
        )
    }

    @Test
    fun `undownloaded metadata language is skipped and general tags never select it`() {
        assertNull(metadataOcrLanguage(listOf(PostTaxonomyTerm("japanese", SearchFacet.TAG))))
        assertEquals(
            listOf(ViewerOcrLanguage.JAPANESE, ViewerOcrLanguage.KOREAN),
            orderedOcrLanguages(
                metadataLanguage = ViewerOcrLanguage.CHINESE,
                enabledLanguages = ViewerOcrLanguage.entries.toSet(),
                readyLanguages = setOf(ViewerOcrLanguage.JAPANESE, ViewerOcrLanguage.KOREAN),
            ),
        )
    }

    @Test
    fun `script evidence rejects Latin watermarks`() {
        assertTrue(containsScriptEvidence("今日は", ViewerOcrLanguage.JAPANESE))
        assertTrue(containsScriptEvidence("中文", ViewerOcrLanguage.CHINESE))
        assertTrue(containsScriptEvidence("한국어", ViewerOcrLanguage.KOREAN))
        assertFalse(containsScriptEvidence("sample 123", ViewerOcrLanguage.JAPANESE))
        assertFalse(containsScriptEvidence("かな", ViewerOcrLanguage.CHINESE))
        assertFalse(containsScriptEvidence("漢字", ViewerOcrLanguage.KOREAN))
    }

    @Test
    fun `normalization clamps coordinates and rejects degenerate polygons`() {
        val polygon = normalizeOcrPolygon(
            points = listOf(
                ViewerOcrPoint(-10f, 10f),
                ViewerOcrPoint(60f, 10f),
                ViewerOcrPoint(60f, 120f),
                ViewerOcrPoint(-10f, 120f),
            ),
            bitmapWidth = 100,
            bitmapHeight = 100,
        )

        assertEquals(0f, requireNotNull(polygon).bounds().left, 0.0001f)
        assertEquals(1f, polygon.bounds().bottom, 0.0001f)
        assertNull(
            normalizeOcrPolygon(
                listOf(ViewerOcrPoint(1f, 1f), ViewerOcrPoint(1f, 1f), ViewerOcrPoint(1f, 1f)),
                100,
                100,
            ),
        )
    }

    @Test
    fun `fit transform and hit test select smallest overlapping region`() {
        val fitted = requireNotNull(fittedImageRect(300f, 300f, imageWidth = 200, imageHeight = 100))
        val transform = ViewerOcrTransform(
            fittedImageRect = fitted,
            layerBounds = ViewerOcrRect(0f, 0f, 300f, 300f),
            zoom = 2f,
            panX = 10f,
            panY = -5f,
        )
        val large = region("large", 0.1f, 0.1f, 0.9f, 0.9f)
        val small = region("small", 0.4f, 0.4f, 0.6f, 0.6f)
        val transformedSmall = transformedOcrPolygon(small.polygon, transform).bounds()

        assertEquals(160f, transformedSmall.center.x, 0.001f)
        assertEquals(145f, transformedSmall.center.y, 0.001f)
        assertEquals(
            small,
            selectOcrRegionAtTap(
                regions = listOf(large, small),
                transform = transform,
                tap = transformedSmall.center,
                hitExpansionPx = 4f,
            ),
        )
    }

    @Test
    fun `translation card prefers below then clamps above`() {
        assertEquals(
            ViewerOcrPoint(50f, 78f),
            placeTranslationCard(
                anchorBounds = ViewerOcrRect(80f, 40f, 120f, 70f),
                viewportWidth = 200f,
                viewportHeight = 200f,
                cardWidth = 100f,
                cardHeight = 40f,
                margin = 8f,
                gap = 8f,
            ),
        )
        assertEquals(
            ViewerOcrPoint(92f, 112f),
            placeTranslationCard(
                anchorBounds = ViewerOcrRect(170f, 160f, 198f, 190f),
                viewportWidth = 200f,
                viewportHeight = 200f,
                cardWidth = 100f,
                cardHeight = 40f,
                margin = 8f,
                gap = 8f,
            ),
        )
    }

    private fun region(id: String, left: Float, top: Float, right: Float, bottom: Float): ViewerOcrRegion {
        return ViewerOcrRegion(
            id = id,
            sourceText = "日本語",
            language = ViewerOcrLanguage.JAPANESE,
            polygon = ViewerOcrPolygon(
                listOf(
                    ViewerOcrPoint(left, top),
                    ViewerOcrPoint(right, top),
                    ViewerOcrPoint(right, bottom),
                    ViewerOcrPoint(left, bottom),
                ),
            ),
        )
    }
}
