package com.theoriacodex.app.viewer

import com.theoriacodex.data.repository.ViewerOcrLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ViewerOcrPhraseGroupingTest {
    @Test
    fun `adjacent Japanese vertical columns merge in right-to-left reading order`() {
        val right = region(
            id = "right",
            text = "こ\nれ\nは",
            language = ViewerOcrLanguage.JAPANESE,
            left = 0.60f,
            top = 0.15f,
            right = 0.66f,
            bottom = 0.75f,
        )
        val left = region(
            id = "left",
            text = "文\n章",
            language = ViewerOcrLanguage.JAPANESE,
            left = 0.52f,
            top = 0.18f,
            right = 0.58f,
            bottom = 0.78f,
        )

        val grouped = groupOcrPhraseRegions(listOf(left, right))

        assertEquals(1, grouped.size)
        assertEquals("これは文章", grouped.single().sourceText)
        assertEquals(ViewerOcrRect(0.52f, 0.15f, 0.66f, 0.78f), grouped.single().polygon.bounds())
    }

    @Test
    fun `vertical singleton removes line-break whitespace before translation`() {
        val vertical = region(
            id = "vertical",
            text = "日\n本\n語",
            language = ViewerOcrLanguage.JAPANESE,
            left = 0.5f,
            top = 0.1f,
            right = 0.56f,
            bottom = 0.7f,
        )

        assertEquals("日本語", groupOcrPhraseRegions(listOf(vertical)).single().sourceText)
    }

    @Test
    fun `terminal punctuation prevents merging the next vertical column`() {
        val complete = region(
            id = "complete",
            text = "終わり。",
            language = ViewerOcrLanguage.JAPANESE,
            left = 0.60f,
            top = 0.15f,
            right = 0.66f,
            bottom = 0.75f,
        )
        val next = region(
            id = "next",
            text = "次です",
            language = ViewerOcrLanguage.JAPANESE,
            left = 0.52f,
            top = 0.18f,
            right = 0.58f,
            bottom = 0.78f,
        )

        assertEquals(2, groupOcrPhraseRegions(listOf(complete, next)).size)
    }

    @Test
    fun `large gaps and vertically separate bubbles remain independent`() {
        val topRight = region(
            id = "top-right",
            text = "右",
            language = ViewerOcrLanguage.JAPANESE,
            left = 0.80f,
            top = 0.05f,
            right = 0.85f,
            bottom = 0.35f,
        )
        val topFarLeft = region(
            id = "top-left",
            text = "左",
            language = ViewerOcrLanguage.JAPANESE,
            left = 0.60f,
            top = 0.05f,
            right = 0.65f,
            bottom = 0.35f,
        )
        val bottom = region(
            id = "bottom",
            text = "下",
            language = ViewerOcrLanguage.JAPANESE,
            left = 0.74f,
            top = 0.60f,
            right = 0.79f,
            bottom = 0.90f,
        )

        assertEquals(3, groupOcrPhraseRegions(listOf(topRight, topFarLeft, bottom)).size)
    }

    @Test
    fun `horizontal Japanese and other languages are unchanged`() {
        val horizontal = region(
            id = "horizontal",
            text = "日本語 の 文",
            language = ViewerOcrLanguage.JAPANESE,
            left = 0.10f,
            top = 0.20f,
            right = 0.70f,
            bottom = 0.28f,
        )
        val chinese = region(
            id = "chinese",
            text = "中\n文",
            language = ViewerOcrLanguage.CHINESE,
            left = 0.80f,
            top = 0.10f,
            right = 0.85f,
            bottom = 0.70f,
        )

        val grouped = groupOcrPhraseRegions(listOf(horizontal, chinese))

        assertSame(horizontal, grouped[0])
        assertSame(chinese, grouped[1])
    }

    private fun region(
        id: String,
        text: String,
        language: ViewerOcrLanguage,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): ViewerOcrRegion {
        return ViewerOcrRegion(
            id = id,
            sourceText = text,
            language = language,
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
