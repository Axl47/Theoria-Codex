package com.theoriacodex.app.viewer.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Trace
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.SuccessResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.viewer.ViewerOcrPoint
import com.theoriacodex.app.viewer.ViewerOcrCrop
import com.theoriacodex.app.viewer.ViewerOcrRegion
import com.theoriacodex.app.viewer.containsScriptEvidence
import com.theoriacodex.app.viewer.deduplicateOcrRegions
import com.theoriacodex.app.viewer.groupOcrPhraseRegions
import com.theoriacodex.app.viewer.japaneseOcrFallbackCrops
import com.theoriacodex.app.viewer.metadataOcrLanguage
import com.theoriacodex.app.viewer.normalizeOcrPolygon
import com.theoriacodex.app.viewer.orderedOcrLanguages
import com.theoriacodex.data.repository.ViewerOcrLanguage
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SourceKey
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.roundToInt
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
        regions: List<ViewerOcrRegion>,
        onStage: (ViewerTranslationStage) -> Unit,
    ): Map<String, String>
}

internal class ViewerOcrTranslationCoordinator(
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val recognizerFactory: CjkTextRecognizerFactory,
    private val translator: ViewerRegionTranslator,
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
        for (language in order) {
            val result = runCatchingPreservingCancellation {
                traceAsyncSection(TRACE_OCR_RECOGNIZE) {
                    recognize(bitmap, language)
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
        regions: List<ViewerOcrRegion>,
        onStage: (ViewerTranslationStage) -> Unit,
    ): Map<String, String> = traceAsyncSection(TRACE_OCR_TRANSLATE) {
        try {
            translator.translate(regions, onStage)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w(
                OCR_LOG_TAG,
                "Viewer translation failed for regions=${regions.size}: " +
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
        bitmap: Bitmap,
        language: ViewerOcrLanguage,
    ): List<ViewerOcrRegion> {
        val recognizer = recognizerFactory.create(language)
        return try {
            val fullImage = OcrBitmapCandidate(
                bitmap = bitmap,
                sourceBounds = Rect(0, 0, bitmap.width, bitmap.height),
                idPrefix = "full",
                owned = false,
            )
            val primary = recognizeCandidate(recognizer, fullImage, bitmap, language)
            if (primary.isNotEmpty() || language != ViewerOcrLanguage.JAPANESE) {
                return groupOcrPhraseRegions(primary)
            }
            val cropped = recognizeJapaneseFallback(recognizer, bitmap, highContrast = false)
            val recovered = cropped.ifEmpty {
                recognizeJapaneseFallback(recognizer, bitmap, highContrast = true)
            }
            groupOcrPhraseRegions(deduplicateOcrRegions(recovered))
        } finally {
            recognizer.close()
        }
    }

    private suspend fun recognizeJapaneseFallback(
        recognizer: TextRecognizer,
        original: Bitmap,
        highContrast: Boolean,
    ): List<ViewerOcrRegion> = buildList {
        japaneseOcrFallbackCrops().forEachIndexed { index, crop ->
            val candidate = prepareCrop(original, crop, highContrast, index)
            try {
                addAll(
                    recognizeCandidate(
                        recognizer = recognizer,
                        candidate = candidate,
                        original = original,
                        language = ViewerOcrLanguage.JAPANESE,
                        fromFallbackCrop = true,
                    ),
                )
            } finally {
                if (candidate.owned) candidate.bitmap.recycle()
            }
        }
    }

    private suspend fun recognizeCandidate(
        recognizer: TextRecognizer,
        candidate: OcrBitmapCandidate,
        original: Bitmap,
        language: ViewerOcrLanguage,
        fromFallbackCrop: Boolean = false,
    ): List<ViewerOcrRegion> {
        val input = InputImage.fromBitmap(candidate.bitmap, 0)
        return recognizer.process(input).await().textBlocks.mapIndexedNotNull { index, block ->
            val sourceText = block.text.trim().takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
            if (!containsScriptEvidence(sourceText, language, fromFallbackCrop)) return@mapIndexedNotNull null
            val candidatePoints = block.cornerPoints
                ?.map { point -> ViewerOcrPoint(point.x.toFloat(), point.y.toFloat()) }
                ?: block.boundingBox?.toViewerOcrPoints()
                ?: return@mapIndexedNotNull null
            val originalPoints = candidatePoints.map { point ->
                candidate.toOriginalPoint(point)
            }
            val polygon = normalizeOcrPolygon(originalPoints, original.width, original.height)
                ?: return@mapIndexedNotNull null
            ViewerOcrRegion(
                id = "${language.name}:${candidate.idPrefix}:$index:${sourceText.hashCode()}",
                sourceText = sourceText,
                language = language,
                polygon = polygon,
            )
        }
    }

    private fun prepareCrop(
        original: Bitmap,
        crop: ViewerOcrCrop,
        highContrast: Boolean,
        index: Int,
    ): OcrBitmapCandidate {
        val left = (crop.left * original.width).roundToInt().coerceIn(0, original.width - 1)
        val top = (crop.top * original.height).roundToInt().coerceIn(0, original.height - 1)
        val right = (crop.right * original.width).roundToInt().coerceIn(left + 1, original.width)
        val bottom = (crop.bottom * original.height).roundToInt().coerceIn(top + 1, original.height)
        val sourceBounds = Rect(left, top, right, bottom)
        val cropped = Bitmap.createBitmap(
            original,
            sourceBounds.left,
            sourceBounds.top,
            sourceBounds.width(),
            sourceBounds.height(),
        )
        val scale = OCR_MAX_BITMAP_EDGE_PX.toFloat() /
            maxOf(sourceBounds.width(), sourceBounds.height())
        val scaled = Bitmap.createScaledBitmap(
            cropped,
            (sourceBounds.width() * scale).roundToInt().coerceAtLeast(1),
            (sourceBounds.height() * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== cropped) cropped.recycle()
        val prepared = if (highContrast) scaled.highContrastGrayscale() else scaled
        if (prepared !== scaled) scaled.recycle()
        return OcrBitmapCandidate(
            bitmap = prepared,
            sourceBounds = sourceBounds,
            idPrefix = if (highContrast) "contrast-$index" else "crop-$index",
            owned = true,
        )
    }
}

private data class OcrBitmapCandidate(
    val bitmap: Bitmap,
    val sourceBounds: Rect,
    val idPrefix: String,
    val owned: Boolean,
) {
    fun toOriginalPoint(point: ViewerOcrPoint): ViewerOcrPoint {
        return ViewerOcrPoint(
            x = sourceBounds.left + point.x * sourceBounds.width() / bitmap.width,
            y = sourceBounds.top + point.y * sourceBounds.height() / bitmap.height,
        )
    }
}

private fun Bitmap.highContrastGrayscale(): Bitmap {
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val grayscale = ColorMatrix().apply { setSaturation(0f) }
    val offset = (1f - OCR_FALLBACK_CONTRAST) * 127.5f
    grayscale.postConcat(
        ColorMatrix(
            floatArrayOf(
                OCR_FALLBACK_CONTRAST, 0f, 0f, 0f, offset,
                0f, OCR_FALLBACK_CONTRAST, 0f, 0f, offset,
                0f, 0f, OCR_FALLBACK_CONTRAST, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    Canvas(output).drawBitmap(
        this,
        0f,
        0f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(grayscale)
        },
    )
    return output
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
private const val OCR_FALLBACK_CONTRAST = 1.8f
private const val TRACE_OCR_DECODE = "TheoriaViewerOcrDecode"
private const val TRACE_OCR_RECOGNIZE = "TheoriaViewerOcrRecognize"
private const val TRACE_OCR_TRANSLATE = "TheoriaViewerTranslate"
private const val OCR_LOG_TAG = "TheoriaViewerOcr"
private val TRACE_COOKIE = AtomicInteger()
