package com.theoriacodex.app.viewer

import com.theoriacodex.data.repository.ViewerOcrLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerOcrFallbackPolicyTest {
    @Test
    fun `fallback crops cover every corner and overlap around the center`() {
        val crops = japaneseOcrFallbackCrops()

        assertEquals(4, crops.size)
        assertTrue(crops.any { it.left == 0f && it.top == 0f })
        assertTrue(crops.any { it.right == 1f && it.top == 0f })
        assertTrue(crops.any { it.left == 0f && it.bottom == 1f })
        assertTrue(crops.any { it.right == 1f && it.bottom == 1f })
        assertTrue(crops.all { it.left <= 0.5f && it.right >= 0.5f })
        assertTrue(crops.all { it.top <= 0.5f && it.bottom >= 0.5f })
    }

    @Test
    fun `overlapping crop detections deduplicate but repeated effects remain`() {
        val first = region("first", "ガッ", 0.40f, 0.10f, 0.60f, 0.40f)
        val duplicate = region("duplicate", "ガ ッ", 0.42f, 0.12f, 0.62f, 0.42f)
        val repeatedElsewhere = region("elsewhere", "ガッ", 0.05f, 0.60f, 0.25f, 0.90f)

        val result = deduplicateOcrRegions(listOf(first, duplicate, repeatedElsewhere))

        assertEquals(listOf("first", "elsewhere"), result.map(ViewerOcrRegion::id))
    }

    private fun region(
        id: String,
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = ViewerOcrRegion(
        id = id,
        sourceText = text,
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
