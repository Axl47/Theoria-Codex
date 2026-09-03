package com.theoriacodex.app.viewer.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Trace
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.SuccessResult
import com.google.mlkit.vision.common.InputImage
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.viewer.ViewerOcrPoint
import com.theoriacodex.app.viewer.ViewerOcrRegion
import com.theoriacodex.app.viewer.containsScriptEvidence
import com.theoriacodex.app.viewer.groupOcrPhraseRegions
import com.theoriacodex.app.viewer.metadataOcrLanguage
import com.theoriacodex.app.viewer.normalizeOcrPolygon
import com.theoriacodex.app.viewer.orderedOcrLanguages
import com.theoriacodex.data.repository.ViewerOcrLanguage
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SourceKey
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class ViewerOcrAnalysisRequest(
    val source: SourceKey,
    val location: String,
    val taxonomy: List<PostTaxonomyTerm>,
    val enabledLanguages: Set<ViewerOcrLanguage>,
    val readyLanguages: Set<ViewerOcrLanguage>,
)

data class ViewerOcrAnalysis(
    val language: ViewerOcrLanguage,
    val regions: List<ViewerOcrRegion>,
    val imageWidth: Int,
    val imageHeight: Int,
)

enum class ViewerTranslationStage {
    PREPARING_TRANSLATOR,
    TRANSLATING,
}

interface ViewerOcrTranslationService {
    suspend fun analyze(request: ViewerOcrAnalysisRequest): ViewerOcrAnalysis?

    suspend fun translate(
        language: ViewerOcrLanguage,
        text: String,
        onStage: (ViewerTranslationStage) -> Unit,
    ): String
}

internal class ViewerOcrTranslationCoordinator(
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val recognizerFactory: CjkTextRecognizerFactory,
    private val translator: ViewerTextTranslator,
) : ViewerOcrTranslationService {
    override suspend fun analyze(request: ViewerOcrAnalysisRequest): ViewerOcrAnalysis? =
        withContext(Dispatchers.Default) {
        val order = orderedOcrLanguages(
            metadataLanguage = metadataOcrLanguage(request.taxonomy),
            enabledLanguages = request.enabledLanguages,
            readyLanguages = request.readyLanguages,
        )
        if (order.isEmpty()) return@withContext null
        val bitmap = traceAsyncSection(TRACE_OCR_DECODE) {
            loadOcrBitmap(request)
        } ?: return@withContext null
        val input = InputImage.fromBitmap(bitmap, 0)
        for (language in order) {
            val result = runCatchingPreservingCancellation {
                traceAsyncSection(TRACE_OCR_RECOGNIZE) {
                    recognize(input, bitmap, language)
                }
            }
            result.exceptionOrNull()?.let { failure ->
                Log.w(
                    OCR_LOG_TAG,
                    "Viewer OCR failed for source=${request.source} language=$language: " +
                        (failure.message ?: failure.javaClass.simpleName),
                )
            }
            val regions = result.getOrNull().orEmpty()
            if (regions.isNotEmpty()) {
                return@withContext ViewerOcrAnalysis(
                    language = language,
                    regions = regions,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                )
            }
        }
        null
    }

    override suspend fun translate(
        language: ViewerOcrLanguage,
        text: String,
        onStage: (ViewerTranslationStage) -> Unit,
    ): String = traceAsyncSection(TRACE_OCR_TRANSLATE) {
        try {
            translator.translate(language, text, onStage)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w(
                OCR_LOG_TAG,
                "Viewer translation failed for language=$language: " +
                    (failure.message ?: failure.javaClass.simpleName),
            )
            throw failure
        }
    }

    private suspend fun loadOcrBitmap(request: ViewerOcrAnalysisRequest): Bitmap? {
        val result = imageLoader.execute(
            MediaRequestFactory.imageRequest(
                context = context,
                url = request.location,
                sourceKey = request.source,
                crossfade = false,
                allowHardware = false,
                targetWidthPx = OCR_MAX_BITMAP_EDGE_PX,
                targetHeightPx = OCR_MAX_BITMAP_EDGE_PX,
            ),
        ) as? SuccessResult ?: return null
        val drawable = result.drawable
        val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: return null
        val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: return null
        val scale = min(
            1f,
            OCR_MAX_BITMAP_EDGE_PX.toFloat() / maxOf(intrinsicWidth, intrinsicHeight),
        )
        val width = (intrinsicWidth * scale).toInt().coerceAtLeast(1)
        val height = (intrinsicHeight * scale).toInt().coerceAtLeast(1)
        return drawable.toBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private suspend fun recognize(
        input: InputImage,
        bitmap: Bitmap,
        language: ViewerOcrLanguage,
    ): List<ViewerOcrRegion> {
        val recognizer = recognizerFactory.create(language)
        return try {
            val regions = recognizer.process(input).await().textBlocks.mapIndexedNotNull { index, block ->
                val sourceText = block.text.trim().takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
                if (!containsScriptEvidence(sourceText, language)) return@mapIndexedNotNull null
                val points = block.cornerPoints
                    ?.map { point -> ViewerOcrPoint(point.x.toFloat(), point.y.toFloat()) }
                    ?: block.boundingBox?.toViewerOcrPoints()
                    ?: return@mapIndexedNotNull null
                val polygon = normalizeOcrPolygon(points, bitmap.width, bitmap.height)
                    ?: return@mapIndexedNotNull null
                ViewerOcrRegion(
                    id = "${language.name}:$index:${sourceText.hashCode()}",
                    sourceText = sourceText,
                    language = language,
                    polygon = polygon,
                )
            }
            groupOcrPhraseRegions(regions)
        } finally {
            recognizer.close()
        }
    }
}

internal fun interface ViewerTextTranslator {
    suspend fun translate(
        language: ViewerOcrLanguage,
        text: String,
        onStage: (ViewerTranslationStage) -> Unit,
    ): String
}

private fun Rect.toViewerOcrPoints(): List<ViewerOcrPoint> = listOf(
    ViewerOcrPoint(left.toFloat(), top.toFloat()),
    ViewerOcrPoint(right.toFloat(), top.toFloat()),
    ViewerOcrPoint(right.toFloat(), bottom.toFloat()),
    ViewerOcrPoint(left.toFloat(), bottom.toFloat()),
)

private suspend inline fun <T> traceAsyncSection(name: String, crossinline block: suspend () -> T): T {
    val cookie = TRACE_COOKIE.incrementAndGet()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.beginAsyncSection(name, cookie)
    return try {
        block()
    } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.endAsyncSection(name, cookie)
    }
}

internal const val OCR_MAX_BITMAP_EDGE_PX = 2_048
private const val TRACE_OCR_DECODE = "TheoriaViewerOcrDecode"
private const val TRACE_OCR_RECOGNIZE = "TheoriaViewerOcrRecognize"
private const val TRACE_OCR_TRANSLATE = "TheoriaViewerTranslate"
private const val OCR_LOG_TAG = "TheoriaViewerOcr"
private val TRACE_COOKIE = AtomicInteger()
