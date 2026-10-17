package com.theoriacodex.app.journeys

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

/** Test-only request observation and pixels; never enabled in the shipped app. */
internal class OfflineImageDiagnostics : Closeable, EventListener.Factory {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val original = Coil.imageLoader(context)
    private val events = ArrayDeque<String>()
    private val loader: ImageLoader = original.newBuilder().eventListenerFactory(this).build()

    init { Coil.setImageLoader(loader) }

    override fun create(request: ImageRequest): EventListener = object : EventListener {
        override fun onStart(request: ImageRequest) = record("start", request)
        override fun onCancel(request: ImageRequest) = record("cancel", request)
        override fun onSuccess(request: ImageRequest, result: SuccessResult) = record(
            "success ${result.dataSource} ${result.drawable.intrinsicWidth}x${result.drawable.intrinsicHeight}", request,
        )
        override fun onError(request: ImageRequest, result: ErrorResult) =
            record("error ${result.throwable.javaClass.name}", request)
    }

    private fun record(state: String, request: ImageRequest) {
        val data = request.data.toString()
        val location = if (File(data).isAbsolute) data else "non-local:${request.data.javaClass.simpleName}"
        synchronized(events) {
            if (events.size == 64) events.removeFirst()
            events.addLast("${SystemClock.elapsedRealtime()} $state $location")
        }
    }

    fun capture(label: String, compose: ComposeTestRule, decor: View, retainedPaths: List<String>): File {
        val outputRoot = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: requireNotNull(context.getExternalFilesDir(null))
        val directory = File(outputRoot, "journey-diagnostics/$label-${SystemClock.elapsedRealtime()}")
        check(directory.mkdirs()) { "Cannot create diagnostic directory" }
        val surface = compose.onAllNodesWithContentDescription("Media actions", useUnmergedTree = true)
            .fetchSemanticsNodes().singleOrNull()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        try {
            screenshot?.let { bitmap ->
                directory.resolve("display.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val geometry = windowGeometry(decor)
            directory.resolve("state.txt").writeText(buildString {
                appendLine("elapsedRealtime=${SystemClock.elapsedRealtime()} label=$label")
                appendLine(geometry.first)
                appendLine("surface.root=${surface?.boundsInRoot} surface.window=${surface?.boundsInWindow}")
                appendLine("surface.positionInRoot=${surface?.positionInRoot} positionInWindow=${surface?.positionInWindow}")
                appendLine("surface.display=${surface?.boundsInWindow?.translate(geometry.second)}")
                appendLine("screenshot=${screenshot?.width}x${screenshot?.height} config=${screenshot?.config} colorSpace=${screenshot?.colorSpace}")
                appendLine("originalWindowSample=${sample(screenshot, surface?.boundsInWindow?.center)}")
                appendLine("displaySample=${sample(screenshot, surface?.boundsInWindow?.center?.plus(geometry.second))}")
                retainedPaths.forEach { appendLine(decodeSummary(it)) }
                appendLine("Coil requests (bounded):")
                synchronized(events) { events.forEach { appendLine(it) } }
                appendLine(compose.onRoot(useUnmergedTree = true).printToString())
            })
        } finally {
            screenshot?.recycle()
        }
        Log.i("OfflineImageJourney", "Diagnostic evidence: ${directory.name}")
        return directory
    }

    private fun windowGeometry(decor: View): Pair<String, Offset> {
        val screen = IntArray(2)
        val window = IntArray(2)
        instrumentation.runOnMainSync {
            decor.getLocationOnScreen(screen)
            decor.getLocationInWindow(window)
        }
        val insets = ViewCompat.getRootWindowInsets(decor)
        val summary = "decor.screen=${screen.toList()} decor.window=${window.toList()} " +
            "size=${decor.width}x${decor.height} " +
            "systemInsets=${insets?.getInsets(WindowInsetsCompat.Type.systemBars())} " +
            "imeInsets=${insets?.getInsets(WindowInsetsCompat.Type.ime())}"
        return summary to Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
    }

    private fun sample(bitmap: Bitmap?, point: Offset?): String {
        if (bitmap == null || point == null) return "unavailable"
        val x = point.x.roundToInt()
        val y = point.y.roundToInt()
        if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return "out-of-bounds ($x,$y)"
        val color = bitmap.getPixel(x, y)
        return "($x,$y) rgba=${Color.red(color)},${Color.green(color)},${Color.blue(color)},${Color.alpha(color)}"
    }

    override fun close() {
        Coil.setImageLoader(original)
        // newBuilder shares the application's caches. Do not shut those caches down here.
    }

    companion object {
        fun decodeSummary(path: String): String {
            val file = File(path)
            val bitmap = BitmapFactory.decodeFile(path) ?: return "retained=$path exists=${file.isFile} bytes=${file.length()} decode=null"
            return try {
                "retained=$path bytes=${file.length()} decode=${bitmap.width}x${bitmap.height} " +
                    "colorSpace=${bitmap.colorSpace} centerArgb=${Integer.toHexString(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))}"
            } finally {
                bitmap.recycle()
            }
        }
    }
}
