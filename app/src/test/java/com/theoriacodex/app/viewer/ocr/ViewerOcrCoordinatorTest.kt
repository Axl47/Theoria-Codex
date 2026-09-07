package com.theoriacodex.app.viewer.ocr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.media.Image
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ImageRequest
import coil.request.ErrorResult
import com.google.android.gms.common.Feature
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.odml.image.MlImage
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.interfaces.Detector
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import com.theoriacodex.app.viewer.bounds
import com.theoriacodex.data.repository.ViewerOcrLanguage
import com.theoriacodex.domain.model.SourceKey
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises Coil, bitmap preparation and production orchestration; only ML Kit recognition is scripted. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerOcrCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private val imageFailures = CopyOnWriteArrayList<Throwable>()

    @Before fun initializePlatformDependencies() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Plain Application excludes ML Kit's manifest startup provider. Its singleton survives
        // cases in one sandbox, so use the SDK's idempotent context entry point. No model is requested.
        MlKitContext.initializeIfNeeded(context)
    }
    @After fun resetMainDispatcher() {
        Dispatchers.resetMain()
        imageFailures.forEach(Throwable::printStackTrace)
        ShadowLog.getLogs().filter { it.tag == "TheoriaViewerOcr" }.forEach { entry ->
            System.err.println("${entry.tag}: ${entry.msg}")
            entry.throwable?.printStackTrace()
        }
    }

    @Test(timeout = 45_000L)
    fun `decoded image is bounded software and successful Japanese columns group without crops`() = runTest(timeout = 30.seconds) {
        val requests = CopyOnWriteArrayList<ImageRequest>()
        val recognizer = RecordingRecognizer { bitmap, _ ->
            Tasks.forResult(text(
                block("文\n章", bitmap, 0.52f, 0.18f, 0.58f, 0.78f),
                block("こ\nれ\nは", bitmap, 0.60f, 0.15f, 0.66f, 0.75f),
            ))
        }
        loader(requests).use { loader ->
            val result = requireNotNull(coordinator(loader, recognizer).analyze(request(fixture(4096, 4096))))
            assertEquals(2048, result.imageWidth)
            assertEquals(2048, result.imageHeight)
            assertEquals("これは文章", result.regions.single().sourceText)
            assertEquals(1, recognizer.inputs.size)
            assertEquals(1, recognizer.closes)
            assertFalse(requests.single().allowHardware)
            assertTrue(requests.single().headers["Referer"]?.contains("pixiv") == true)
            assertTrue(recognizer.inputs.all { it.config != Bitmap.Config.HARDWARE })
        }
    }

    @Test(timeout = 45_000L)
    fun `empty Japanese pass maps lower right crop back and recycles all crop bitmaps`() = runTest(timeout = 30.seconds) {
        val recognizer = RecordingRecognizer { bitmap, index ->
            Tasks.forResult(if (index == 4) {
                text(block("日本語", bitmap, 0.25f, 0.25f, 0.75f, 0.5f))
            } else text())
        }
        loader().use { loader ->
            val result = requireNotNull(coordinator(loader, recognizer).analyze(request(fixture())))
            assertEquals(5, recognizer.inputs.size) // Full image + four ordinary crops, no contrast pass.
            val bounds = result.regions.single().polygon.bounds()
            assertEquals(0.565f, bounds.left, 0.002f)
            assertEquals(0.565f, bounds.top, 0.002f)
            assertEquals(0.855f, bounds.right, 0.002f)
            assertEquals(0.71f, bounds.bottom, 0.002f)
            assertTrue(recognizer.inputs.drop(1).all(Bitmap::isRecycled))
            assertTrue(recognizer.dimensions.all { (width, height) -> maxOf(width, height) <= 2048 })
            assertEquals(1, recognizer.closes)
        }
    }

    @Test(timeout = 45_000L)
    fun `contrast recovery runs only after both empty passes and other languages never crop`() = runTest(timeout = 30.seconds) {
        val japanese = RecordingRecognizer { bitmap, index ->
            Tasks.forResult(if (index == 5) text(block("日本語", bitmap, 0.2f, 0.2f, 0.7f, 0.4f)) else text())
        }
        loader().use { loader ->
            assertTrue(requireNotNull(coordinator(loader, japanese).analyze(request(fixture()))).regions.isNotEmpty())
            assertEquals(9, japanese.inputs.size)
            assertTrue(japanese.inputs.drop(1).all(Bitmap::isRecycled))
            assertTrue(japanese.sampleColors.drop(5).all { color -> Color.red(color) == Color.green(color) })
            assertTrue(japanese.sampleColors.take(5).any { color -> Color.red(color) != Color.green(color) })
        }
        listOf(ViewerOcrLanguage.CHINESE, ViewerOcrLanguage.KOREAN).forEach { language ->
            val recognizer = RecordingRecognizer { _, _ -> Tasks.forResult(text()) }
            loader().use { loader ->
                assertNull(coordinator(loader, recognizer).analyze(request(fixture(), language)))
                assertEquals(1, recognizer.inputs.size)
                assertEquals(1, recognizer.closes)
            }
        }
    }

    @Test(timeout = 45_000L)
    fun `overlapping crop detections deduplicate before grouping and Latin watermark is ignored`() = runTest(timeout = 30.seconds) {
        val recognizer = RecordingRecognizer { bitmap, index ->
            Tasks.forResult(when (index) {
                0 -> text(block("watermark", bitmap, 0.1f, 0.1f, 0.5f, 0.2f))
                1 -> text(block("日本語", bitmap, 0.78f, 0.2f, 0.98f, 0.3f))
                2 -> text(block("日本語", bitmap, 0.056f, 0.2f, 0.256f, 0.3f))
                else -> text()
            })
        }
        loader().use { loader ->
            val result = requireNotNull(coordinator(loader, recognizer).analyze(request(fixture())))
            assertEquals(listOf("日本語"), result.regions.map { it.sourceText })
            assertEquals(5, recognizer.inputs.size)
        }
    }

    @Test(timeout = 45_000L)
    fun `normal mixed-script label remains accepted without extra recovery passes`() = runTest(timeout = 30.seconds) {
        val recognizer = RecordingRecognizer { bitmap, _ ->
            Tasks.forResult(text(block("新WINDOWS", bitmap, 0.1f, 0.1f, 0.9f, 0.3f)))
        }
        loader().use { loader ->
            val result = requireNotNull(coordinator(loader, recognizer).analyze(request(fixture())))
            assertEquals(listOf("新WINDOWS"), result.regions.map { it.sourceText })
            assertEquals(1, recognizer.inputs.size)
        }
    }

    @Test(timeout = 45_000L)
    fun `Latin watermark recovery artifacts never publish regions`() = runTest(timeout = 30.seconds) {
        val recognizer = RecordingRecognizer { bitmap, index ->
            val recognized = if (index == 0) "SAMPLE WATERMARK 2026" else "三RMARK 2026"
            Tasks.forResult(text(block(recognized, bitmap, 0.1f, 0.1f, 0.9f, 0.3f)))
        }
        loader().use { loader ->
            assertNull(coordinator(loader, recognizer).analyze(request(fixture())))
            assertEquals(9, recognizer.inputs.size)
            assertEquals(1, recognizer.closes)
            assertTrue(recognizer.inputs.drop(1).all(Bitmap::isRecycled))
        }
    }

    @Test(timeout = 45_000L)
    fun `cancelled crop recognition closes recognizer and recycles current crop`() = runTest(timeout = 30.seconds) {
        val cropStarted = CompletableDeferred<Unit>()
        val recognizer = RecordingRecognizer { _, index ->
            if (index == 0) Tasks.forResult(text()) else {
                cropStarted.complete(Unit)
                TaskCompletionSource<Text>().task
            }
        }
        loader().use { loader ->
            val owner = coordinator(loader, recognizer)
            val image = fixture()
            val pending = async(Dispatchers.Default) { owner.analyze(request(image)) }
            select {
                cropStarted.onAwait { }
                pending.onAwait { error("Analysis exited before crop recognition: $it") }
            }
            pending.cancelAndJoin()
            assertEquals(1, recognizer.closes)
            assertEquals(2, recognizer.inputs.size)
            assertTrue(recognizer.inputs.last().isRecycled)
        }
    }

    private fun loader(requests: MutableList<ImageRequest> = CopyOnWriteArrayList()): ImageLoader =
        ImageLoader.Builder(context).components {
            add(Interceptor { chain ->
                requests += chain.request
                chain.proceed(chain.request).also { result ->
                    if (result is ErrorResult) {
                        imageFailures += result.throwable
                    }
                }
            })
        }.build()

    private fun coordinator(loader: ImageLoader, recognizer: RecordingRecognizer) = ViewerOcrTranslationCoordinator(
        context, loader, CjkTextRecognizerFactory { recognizer },
        object : ViewerRegionTranslator {
            override suspend fun translate(
                regions: List<com.theoriacodex.app.viewer.ViewerOcrRegion>,
                onStage: (ViewerTranslationStage) -> Unit,
            ): Map<String, String> = error("Analysis must never translate")
        },
    )

    private fun request(location: String, language: ViewerOcrLanguage = ViewerOcrLanguage.JAPANESE) =
        ViewerOcrAnalysisRequest(SourceKey.PIXIV, location, emptyList(), setOf(language), setOf(language))

    private fun fixture(width: Int = 400, height: Int = 400): String {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.rgb(180, 70, 30))
        Canvas(image).drawRect(width * 0.6f, height * 0.5f, width * 0.9f, height * 0.8f,
            Paint().apply { color = Color.BLACK })
        val file = temporary.newFile("image-${System.nanoTime()}.png")
        file.outputStream().use { assertTrue("Fixture PNG encode failed", image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("Fixture PNG is empty", file.length() > 0)
        image.recycle()
        return file.toURI().toString()
    }
}

private class RecordingRecognizer(
    private val response: (Bitmap, Int) -> Task<Text>,
) : TextRecognizer {
    val inputs = CopyOnWriteArrayList<Bitmap>()
    val dimensions = CopyOnWriteArrayList<Pair<Int, Int>>()
    val sampleColors = CopyOnWriteArrayList<Int>()
    var closes = 0
        private set

    override fun process(image: InputImage): Task<Text> {
        val bitmap = requireNotNull(image.bitmapInternal)
        val index = inputs.size
        inputs += bitmap
        dimensions += bitmap.width to bitmap.height
        sampleColors += bitmap.getPixel(0, 0)
        return response(bitmap, index)
    }
    override fun close() { closes++ }
    override fun getDetectorType() = Detector.TYPE_TEXT_RECOGNITION
    override fun getOptionalFeatures(): Array<Feature> = emptyArray()
    override fun process(image: MlImage): Task<Text> = error("Expected bitmap InputImage")
    override fun process(bitmap: Bitmap, rotationDegrees: Int): Task<Text> = error("Expected InputImage")
    override fun process(image: Image, rotationDegrees: Int): Task<Text> = error("Expected InputImage")
    override fun process(image: Image, rotationDegrees: Int, matrix: Matrix): Task<Text> = error("Expected InputImage")
    override fun process(buffer: ByteBuffer, width: Int, height: Int, rotationDegrees: Int, format: Int): Task<Text> =
        error("Expected InputImage")
}

private fun text(vararg blocks: Text.TextBlock) = Text(blocks.joinToString("\n") { it.text }, blocks.toList())

private fun block(text: String, bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Text.TextBlock {
    val bounds = Rect((bitmap.width * left).toInt(), (bitmap.height * top).toInt(),
        (bitmap.width * right).toInt(), (bitmap.height * bottom).toInt())
    return Text.TextBlock(text, bounds, listOf(Point(bounds.left, bounds.top), Point(bounds.right, bounds.top),
        Point(bounds.right, bounds.bottom), Point(bounds.left, bounds.bottom)), "ja", Matrix(), emptyList<Text.Line>())
}

private inline fun <T> ImageLoader.use(block: (ImageLoader) -> T): T = try { block(this) } finally { shutdown() }
