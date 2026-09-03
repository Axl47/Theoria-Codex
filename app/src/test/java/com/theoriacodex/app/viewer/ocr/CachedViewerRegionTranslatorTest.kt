package com.theoriacodex.app.viewer.ocr

import com.theoriacodex.app.viewer.ViewerOcrPoint
import com.theoriacodex.app.viewer.ViewerOcrPolygon
import com.theoriacodex.app.viewer.ViewerOcrRegion
import com.theoriacodex.data.repository.InMemoryViewerTranslationCacheRepository
import com.theoriacodex.data.repository.ViewerOcrLanguage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CachedViewerRegionTranslatorTest {
    @Test
    fun `deduplicates phrases and reuses durable cache without another remote call`() = runTest {
        val remote = FakeViewerTextTranslator()
        val translator = CachedViewerRegionTranslator(
            remote = remote,
            cache = InMemoryViewerTranslationCacheRepository(),
        )
        val regions = listOf(region("one", "ガッ"), region("two", "ガッ"), region("three", "あっ"))

        val first = translator.translate(regions) {}
        val second = translator.translate(regions) {}

        assertEquals(
            mapOf("one" to "Gah!", "two" to "Gah!", "three" to "Ah!"),
            first,
        )
        assertEquals(first, second)
        assertEquals(listOf(listOf("ガッ", "あっ")), remote.requests)
    }

    private fun region(id: String, text: String) = ViewerOcrRegion(
        id = id,
        sourceText = text,
        language = ViewerOcrLanguage.JAPANESE,
        polygon = ViewerOcrPolygon(
            listOf(
                ViewerOcrPoint(0f, 0f),
                ViewerOcrPoint(1f, 0f),
                ViewerOcrPoint(1f, 1f),
                ViewerOcrPoint(0f, 1f),
            ),
        ),
    )
}

private class FakeViewerTextTranslator : ViewerTextTranslator {
    val requests = mutableListOf<List<String>>()

    override suspend fun translateBatch(
        language: ViewerOcrLanguage,
        sourceTexts: List<String>,
        onStage: (ViewerTranslationStage) -> Unit,
    ): Map<String, String> {
        requests += sourceTexts
        return sourceTexts.associateWith { text ->
            when (text) {
                "ガッ" -> "Gah!"
                "あっ" -> "Ah!"
                else -> "Translated"
            }
        }
    }
}
