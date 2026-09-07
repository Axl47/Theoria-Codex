package com.theoriacodex.app.media

import android.os.PowerManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.imageLoader
import com.theoriacodex.app.viewer.ViewerOcrRegion
import com.theoriacodex.app.viewer.ocr.GooglePlayOcrLanguageModuleGateway
import com.theoriacodex.app.viewer.ocr.MlKitCjkTextRecognizerFactory
import com.theoriacodex.app.viewer.ocr.ViewerOcrAnalysisRequest
import com.theoriacodex.app.viewer.ocr.ViewerOcrTranslationCoordinator
import com.theoriacodex.app.viewer.ocr.ViewerRegionTranslator
import com.theoriacodex.app.viewer.ocr.ViewerTranslationStage
import com.theoriacodex.data.repository.ViewerOcrLanguage
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Real downloaded ML Kit recognizers over readable static-image fixtures; never downloads a model. */
@RunWith(AndroidJUnit4::class)
class ViewerOcrCorpusDeviceTest {
    @get:Rule val screenAwake = object : ExternalResource() {
        private var wakeLock: PowerManager.WakeLock? = null

        @Suppress("DEPRECATION")
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            wakeLock = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Theoria:OcrCorpus")
                .apply { acquire(65_000L) }
        }

        override fun after() {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    @Test(timeout = 60_000L) fun japaneseHorizontalText() = recognize(ViewerOcrLanguage.JAPANESE, "今日はいい天気です", "天気")
    @Test(timeout = 60_000L) fun japaneseVerticalText() = recognize(ViewerOcrLanguage.JAPANESE, "日本語の文章", "日本語", vertical = true)
    @Test(timeout = 60_000L) fun chineseText() = recognize(ViewerOcrLanguage.CHINESE, "今天天气很好", "天气")
    @Test(timeout = 60_000L) fun koreanText() = recognize(ViewerOcrLanguage.KOREAN, "오늘 날씨가 좋아요", "날씨")

    @Test(timeout = 60_000L)
    fun latinWatermarkNeverBecomesCjkText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = MlKitCjkTextRecognizerFactory()
        val gateway = GooglePlayOcrLanguageModuleGateway(context, factory)
        val ready = ViewerOcrLanguage.entries.filter { gateway.isDownloaded(it) }.toSet()
        assumeTrue("No shared CJK OCR model is ready; Latin-negative recognition was not exercised", ready.isNotEmpty())
        withCorpusImage("SAMPLE WATERMARK 2026", vertical = false) { file ->
            val coordinator = ViewerOcrTranslationCoordinator(context, context.imageLoader, factory, NoTranslation)
            assertNull(coordinator.analyze(ViewerOcrAnalysisRequest(SourceKey.PIXIV,
                file.toURI().toString(), emptyList(), ready, ready)))
        }
    }

    private fun recognize(language: ViewerOcrLanguage, text: String, expected: String, vertical: Boolean = false) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = MlKitCjkTextRecognizerFactory()
        val ready = GooglePlayOcrLanguageModuleGateway(context, factory).isDownloaded(language)
        assumeTrue("$language shared OCR model is not downloaded; real recognition was not exercised", ready)
        withCorpusImage(text, vertical) { file ->
            val result = ViewerOcrTranslationCoordinator(context, context.imageLoader, factory, NoTranslation)
                .analyze(ViewerOcrAnalysisRequest(SourceKey.PIXIV, file.toURI().toString(), emptyList(),
                    setOf(language), setOf(language)))
            val recognized = result?.regions.orEmpty().joinToString("") { it.sourceText }.filterNot(Char::isWhitespace)
            assertTrue("Expected $language phrase '$expected', recognized '$recognized'", expected in recognized)
            assertTrue(result?.regions.orEmpty().all { region ->
                region.polygon.points.all { point -> point.x in 0f..1f && point.y in 0f..1f }
            })
        }
    }

    private suspend fun withCorpusImage(text: String, vertical: Boolean, block: suspend (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 96f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        if (vertical) text.forEachIndexed { index, character ->
            canvas.drawText(character.toString(), 950f, 170f + index * 125f, paint)
        } else {
            canvas.drawText(text, 80f, 450f, paint)
        }
        val file = File.createTempFile("ocr-corpus-", ".png", context.cacheDir)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            block(file)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            file.delete()
        }
    }

    private object NoTranslation : ViewerRegionTranslator {
        override suspend fun translate(regions: List<ViewerOcrRegion>, onStage: (ViewerTranslationStage) -> Unit): Map<String, String> =
            error("Recognition corpus must not send any translation request")
    }
}
