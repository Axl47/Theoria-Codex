package com.theoriacodex.macrobenchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val JOURNEY_TIMEOUT_MS = 15_000L
private val MEDIA_PATTERN = Pattern.compile("search_media_pixiv_benchmark_(video|gif|webp|ugoira)_.*")
private val VIEWER_PATTERN = Pattern.compile("viewer_video_pixiv_benchmark_video_.*")
private const val STATUS_TAG = "benchmark_journey_status"

/** Uses the real bottom navigation, collection card and creator action; no synthetic feed switching. */
internal fun UiDevice.completeFiveFeedJourney() {
    exerciseCurrentFeed("Search")
    clickDestination("For You")
    exerciseCurrentFeed("For You")
    clickDestination("Recents")
    clickText("Watched")
    exerciseCurrentFeed("Watched Recents")
    clickDestination("Codex")
    clickText("Benchmark collection")
    exerciseCurrentFeed("Codex detail")
    pressBack()
    clickDestination("Search")
    requireMovingFeedPreviews("Search before creator")
    findObjects(By.res(MEDIA_PATTERN)).first().longClick()
    clickText("Benchmark creator")
    exerciseCurrentFeed("Creator Profile")
    pressBack()
    clickDestination("Settings")
    requireNoPreviewLeases()
    clickDestination("Search")
    requireMovingFeedPreviews("Search after tab return")
    val video = findObjects(By.res(MEDIA_PATTERN)).firstOrNull { "_video_" in it.resourceName.orEmpty() }
        ?: error("Search fixture must present a video for real Viewer entry")
    video.click()
    requireChangingMedia(VIEWER_PATTERN, "Viewer opened from Search", minimumVisible = 1)
    pressBack()
    requireMovingFeedPreviews("Search after Viewer return")
    val sessionBeforeBackground = findObject(By.res(STATUS_TAG))?.contentDescription
        ?.substringBefore(" settled=") ?: error("Missing fixture session identity")
    requirePreviewLeaseCount("foreground Search") { it > 0 }
    pressHome()
    requirePreviewLeaseCount("background Home") { it == 0 }
    // Reorder the existing fixture activity; no CLEAR_TASK and no production launcher activity.
    val context = InstrumentationRegistry.getInstrumentation().context
    context.startActivity(Intent().setComponent(ComponentName("com.theoriacodex.benchmark",
        "com.theoriacodex.app.benchmark.BenchmarkFixtureActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    val resumed = wait(Until.findObject(By.res(STATUS_TAG)), JOURNEY_TIMEOUT_MS)
        ?: error("Could not resume the existing fixture activity")
    check(resumed.contentDescription?.substringBefore(" settled=") == sessionBeforeBackground) {
        "Background resume recreated the fixture instead of retaining the route owners"
    }
    requireMovingFeedPreviews("Search after background resume")
}

private fun UiDevice.exerciseCurrentFeed(label: String) {
    val observedKinds = mutableSetOf<String>()
    fun verify(step: String) {
        requireMovingFeedPreviews(step)
        visiblePreviews().keys.forEach { identity ->
            val match = MEDIA_PATTERN.matcher(identity)
            if (match.find()) observedKinds += match.group(1)
        }
    }
    verify(label)
    repeat(3) {
        flingFeed(Direction.DOWN)
        verify("$label down $it")
    }
    repeat(3) {
        flingFeed(Direction.UP)
        verify("$label up $it")
    }
    check(observedKinds == setOf("video", "gif", "webp", "ugoira")) {
        "$label did not exercise all four animated media kinds: $observedKinds"
    }
}

private fun UiDevice.flingFeed(direction: Direction) {
    val grid = findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
        ?: error("Current real feed has no scrollable grid")
    grid.setGestureMargin(displayWidth / 6)
    grid.fling(direction)
}

/**
 * Samples the actual image surface, so paused frames, spinner-only previews, and isPlaying-only
 * reports cannot satisfy the test. All visible media identities must advance, including siblings.
 */
internal fun UiDevice.requireMovingFeedPreviews(label: String) = requireChangingMedia(MEDIA_PATTERN, label, minimumVisible = 2)

private fun UiDevice.requireChangingMedia(pattern: Pattern, label: String, minimumVisible: Int) {
    val deadline = SystemClock.elapsedRealtime() + JOURNEY_TIMEOUT_MS
    while (SystemClock.elapsedRealtime() < deadline) {
        waitForIdle(500)
        val previews = visiblePreviews(pattern)
        if (previews.size < minimumVisible) {
            SystemClock.sleep(100)
            continue
        }
        var previous = screenshotSamples(previews)
        val advances = previews.keys.associateWith { 0 }.toMutableMap()
        repeat(8) {
            SystemClock.sleep(150)
            val current = visiblePreviews(pattern)
            if (current != previews) return@repeat
            val samples = screenshotSamples(current)
            previous.forEach { (identity, before) ->
                if (pixelsAdvanced(before, samples.getValue(identity))) {
                    advances[identity] = advances.getValue(identity) + 1
                }
            }
            previous = samples
            if (advances.values.all { it >= 2 }) return
        }
    }
    error("$label: every visibly presented MP4/GIF/WebP/Ugoira preview must render changing frames; visible=${visiblePreviews(pattern).keys}")
}

private fun UiDevice.visiblePreviews(pattern: Pattern = MEDIA_PATTERN): Map<String, Rect> = findObjects(By.res(pattern))
    .mapNotNull { node ->
        val bounds = node.visibleBounds
        node.resourceName?.takeIf { bounds.width() > 0 && bounds.height() > 0 }?.let { it to bounds }
    }.toMap()

private fun screenshotSamples(previews: Map<String, Rect>): Map<String, IntArray> {
    val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
    return try { previews.mapValues { (_, bounds) -> screenshot.sample(bounds) } } finally { screenshot.recycle() }
}

private fun Bitmap.sample(bounds: Rect): IntArray = IntArray(96) { index ->
    val x = bounds.left + bounds.width() * (index % 12 + 1) / 13
    val y = bounds.top + bounds.height() * (index / 12 + 1) / 9
    getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
}

private fun pixelsAdvanced(before: IntArray, after: IntArray): Boolean = before.indices.count { index ->
    val a = before[index]
    val b = after[index]
    kotlin.math.abs(Color.red(a) - Color.red(b)) + kotlin.math.abs(Color.green(a) - Color.green(b)) +
        kotlin.math.abs(Color.blue(a) - Color.blue(b)) > 30
} >= 12

private fun UiDevice.clickDestination(label: String) {
    val item = wait(Until.findObject(By.desc(label)), JOURNEY_TIMEOUT_MS)
        ?: error("Bottom navigation '$label' was unavailable")
    item.click()
}

private fun UiDevice.clickText(label: String) {
    val item = wait(Until.findObject(By.text(label)), JOURNEY_TIMEOUT_MS)
        ?: error("Real route action '$label' was unavailable")
    item.click()
}

private fun UiDevice.requireNoPreviewLeases() {
    val deadline = SystemClock.elapsedRealtime() + JOURNEY_TIMEOUT_MS
    while (SystemClock.elapsedRealtime() < deadline) {
        val status = findObject(By.res(STATUS_TAG))?.contentDescription.orEmpty()
        if (status.endsWith("active=0")) return
        SystemClock.sleep(100)
    }
    error("Leaving all feeds for Settings retained a preview player lease")
}

internal fun UiDevice.requireIntegratedDurationSettled() {
    val deadline = SystemClock.elapsedRealtime() + JOURNEY_TIMEOUT_MS
    while (SystemClock.elapsedRealtime() < deadline) {
        val status = findObject(By.res(STATUS_TAG))?.contentDescription.orEmpty()
        if ("settled=24/24" in status) {
            val ranges = Regex("ranges=(\\d+)").find(status)?.groupValues?.get(1)?.toLong() ?: 0
            val bytes = Regex("bytes=(\\d+)").find(status)?.groupValues?.get(1)?.toLong() ?: 0
            check(ranges > 0 && bytes in 1..ranges * 256 * 1024) {
                "Duration journey did not exercise bounded production acquisition: $status"
            }
            return
        }
        SystemClock.sleep(100)
    }
    error("Production feed demand did not settle all 24 duration decisions")
}


/** The existing benchmark-only receiver answers from the actual application-owned player pool. */
private fun requirePreviewLeaseCount(label: String, expected: (Int) -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + JOURNEY_TIMEOUT_MS
    var lastCount = -1
    while (SystemClock.elapsedRealtime() < deadline) {
        lastCount = previewLeaseSnapshot()
        if (expected(lastCount)) return
        SystemClock.sleep(100)
    }
    error("$label retained an unexpected preview lease count: $lastCount")
}

private fun previewLeaseSnapshot(): Int {
    val completed = CountDownLatch(1)
    var count: Int? = null
    val reply = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            count = resultData?.toIntOrNull()
            completed.countDown()
        }
    }
    val request = Intent("com.theoriacodex.action.BENCHMARK_PLAYBACK_SNAPSHOT")
        .setComponent(ComponentName("com.theoriacodex.benchmark",
            "com.theoriacodex.app.benchmark.BenchmarkDurationStartReceiver"))
    InstrumentationRegistry.getInstrumentation().context.sendOrderedBroadcast(
        request, null, reply, Handler(Looper.getMainLooper()), 0, null, null,
    )
    check(completed.await(2, TimeUnit.SECONDS)) { "Benchmark pool diagnostic did not answer" }
    return requireNotNull(count) { "Benchmark pool diagnostic returned no count" }
}
