package com.theoriacodex.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

private const val TARGET_PACKAGE = "com.theoriacodex.benchmark"
private const val FIXTURE_ACTIVITY = "com.theoriacodex.app.benchmark.BenchmarkFixtureActivity"
private const val ACTION_BENCHMARK_FIXTURE = "com.theoriacodex.action.BENCHMARK_FIXTURE"
private const val ACTION_BENCHMARK_DURATION_START =
    "com.theoriacodex.action.BENCHMARK_DURATION_START"
private const val DURATION_START_RECEIVER =
    "com.theoriacodex.app.benchmark.BenchmarkDurationStartReceiver"
private const val EXTRA_SCENARIO = "benchmark_scenario"
private const val FIXTURE_PROCESS_SUFFIX = ":benchmarkFixture"
private const val SCENARIO_SEARCH = "search"
private const val SCENARIO_SEARCH_DURATION = "search_duration"
private const val SCENARIO_VIEWER = "viewer"
private const val SEARCH_GRID_TAG = "benchmark_search_grid"
private const val DURATION_STATUS_TAG = "benchmark_duration_status"
private const val DURATION_SETTLED_DESCRIPTION = "Settled 24/24"
private const val SEARCH_VIDEO_TAG_PREFIX = "search_video_rule34video_benchmark_search_"
private const val VIEWER_VIDEO_TAG_PREFIX = "viewer_video_rule34video_benchmark_viewer_"
private const val STARTUP_ITERATIONS = 10
private const val INTERACTION_ITERATIONS = 5
private const val SEARCH_SCROLL_STEPS = 3
private const val VIEWER_PAGE_COUNT = 6
private const val UI_TIMEOUT_MS = 15_000L
private const val POLL_INTERVAL_MS = 50L
private const val TRACE_PREVIEW_PREPARE = "TheoriaPreviewPrepare"
private const val TRACE_PREVIEW_FIRST_FRAME = "TheoriaPreviewFirstFrame"
private const val TRACE_VIEWER_PREPARE = "TheoriaViewerPrepare"
private const val TRACE_VIEWER_FIRST_FRAME = "TheoriaViewerFirstFrame"
private const val TRACE_MEDIA_LOAD = "TheoriaMediaLoad"
private const val TRACE_DURATION_DEMAND = "TheoriaDurationDemand"
private const val TRACE_DURATION_RESOLVE = "TheoriaDurationResolve"
private const val TRACE_DURATION_PROBE = "TheoriaDurationProbe"
private const val TRACE_DURATION_PUBLISH = "TheoriaDurationPublish"
private const val TRACE_DURATION_SETTLED = "TheoriaDurationSettled"
private const val TRACE_DURATION_WORKLOAD = "TheoriaDurationWorkload"
private const val TRACE_DURATION_BATCH = "TheoriaDurationBatch"

private val COMPILATION_MODE = CompilationMode.Partial(
    baselineProfileMode = BaselineProfileMode.Require,
)

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class TheoriaMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = COMPILATION_MODE,
        startupMode = StartupMode.COLD,
        iterations = STARTUP_ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    @Test
    fun warmStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = COMPILATION_MODE,
        startupMode = StartupMode.WARM,
        iterations = STARTUP_ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    @Test
    fun searchConcurrentAutoplayScroll() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            fixtureFrameTimingMetric(),
            fixtureMemoryUsageMetric(),
            countMetric(TRACE_PREVIEW_PREPARE, "previewPrepare"),
            countMetric(TRACE_PREVIEW_FIRST_FRAME, "previewFirstFrame"),
            countMetric(TRACE_MEDIA_LOAD, "mediaLoad"),
        ),
        compilationMode = COMPILATION_MODE,
        startupMode = null,
        iterations = INTERACTION_ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait(fixtureIntent(SCENARIO_SEARCH))
            device.requireObject(SEARCH_GRID_TAG, "Search fixture grid")
            device.requireAllVisibleSearchVideosPlaying()
        },
    ) {
        val grid = device.requireObject(SEARCH_GRID_TAG, "Search fixture grid")
        grid.setGestureMargin(device.displayWidth / 6)
        repeat(SEARCH_SCROLL_STEPS) {
            grid.fling(Direction.DOWN)
            device.requireAllVisibleSearchVideosPlaying()
        }
        repeat(SEARCH_SCROLL_STEPS) {
            grid.fling(Direction.UP)
            device.requireAllVisibleSearchVideosPlaying()
        }
    }

    @Test
    fun searchDurationEnrichmentConcurrentAutoplayScroll() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            fixtureFrameTimingMetric(),
            fixtureMemoryUsageMetric(),
            countMetric(TRACE_PREVIEW_PREPARE, "previewPrepare"),
            countMetric(TRACE_PREVIEW_FIRST_FRAME, "previewFirstFrame"),
            countMetric(TRACE_MEDIA_LOAD, "mediaLoad"),
            countMetric(TRACE_DURATION_DEMAND, "durationDemand"),
            countMetric(TRACE_DURATION_RESOLVE, "durationResolve"),
            countMetric(TRACE_DURATION_PROBE, "durationProbe"),
            countMetric(TRACE_DURATION_PUBLISH, "durationPublish"),
            countMetric(TRACE_DURATION_SETTLED, "durationSettled"),
            durationWorkloadMetric(),
            durationBatchMetric(),
        ),
        compilationMode = COMPILATION_MODE,
        startupMode = null,
        iterations = INTERACTION_ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait(fixtureIntent(SCENARIO_SEARCH_DURATION))
            device.requireObject(SEARCH_GRID_TAG, "Duration Search fixture grid")
            device.requireDurationWaiting()
            device.requireAllVisibleSearchVideosPlaying()
        },
    ) {
        sendDurationStartSignal()
        val grid = device.requireObject(SEARCH_GRID_TAG, "Duration Search fixture grid")
        grid.setGestureMargin(device.displayWidth / 6)
        repeat(SEARCH_SCROLL_STEPS) {
            grid.fling(Direction.DOWN)
            device.requireAllVisibleSearchVideosPlaying()
        }
        repeat(SEARCH_SCROLL_STEPS) {
            grid.fling(Direction.UP)
            device.requireAllVisibleSearchVideosPlaying()
        }
        device.requireDurationSettled()
        device.requireAllVisibleSearchVideosPlaying()
    }

    @Test
    fun viewerRepeatedSwipes() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            fixtureFrameTimingMetric(),
            fixtureMemoryUsageMetric(),
            countMetric(TRACE_VIEWER_PREPARE, "viewerPrepare"),
            countMetric(TRACE_VIEWER_FIRST_FRAME, "viewerFirstFrame"),
            countMetric(TRACE_MEDIA_LOAD, "mediaLoad"),
        ),
        compilationMode = COMPILATION_MODE,
        startupMode = null,
        iterations = INTERACTION_ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait(fixtureIntent(SCENARIO_VIEWER))
            device.requireViewerVideoPlaying(page = 0)
        },
    ) {
        for (page in 1 until VIEWER_PAGE_COUNT) {
            device.swipeViewer(next = true)
            device.requireViewerVideoPlaying(page)
        }
        for (page in (VIEWER_PAGE_COUNT - 2) downTo 0) {
            device.swipeViewer(next = false)
            device.requireViewerVideoPlaying(page)
        }
    }

    private fun fixtureIntent(scenario: String): Intent {
        return Intent(ACTION_BENCHMARK_FIXTURE)
            .setComponent(ComponentName(TARGET_PACKAGE, FIXTURE_ACTIVITY))
            .putExtra(EXTRA_SCENARIO, scenario)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    private fun countMetric(section: String, label: String): TraceSectionMetric {
        return TraceSectionMetric(
            sectionName = section,
            mode = TraceSectionMetric.Mode.Count,
            label = label,
            targetPackageOnly = false,
        )
    }

    private fun durationWorkloadMetric(): TraceSectionMetric {
        return TraceSectionMetric(
            sectionName = TRACE_DURATION_WORKLOAD,
            mode = TraceSectionMetric.Mode.Sum,
            label = "durationWorkload",
            targetPackageOnly = false,
        )
    }

    private fun durationBatchMetric(): TraceSectionMetric {
        return TraceSectionMetric(
            sectionName = TRACE_DURATION_BATCH,
            mode = TraceSectionMetric.Mode.Sum,
            label = "durationBatch",
            targetPackageOnly = false,
        )
    }

    private fun fixtureFrameTimingMetric(): FrameTimingMetric {
        return FrameTimingMetric(
            processNameSuffix = FIXTURE_PROCESS_SUFFIX,
            metricNameSuffix = "Fixture",
        )
    }

    private fun fixtureMemoryUsageMetric(): MemoryUsageMetric {
        return MemoryUsageMetric(
            mode = MemoryUsageMetric.Mode.Max,
            processNameSuffix = FIXTURE_PROCESS_SUFFIX,
            metricNameSuffix = "Fixture",
        )
    }

}

private fun sendDurationStartSignal() {
    val signal = Intent(ACTION_BENCHMARK_DURATION_START).setComponent(
        ComponentName(TARGET_PACKAGE, DURATION_START_RECEIVER),
    )
    InstrumentationRegistry.getInstrumentation().context.sendBroadcast(signal)
}

private fun UiDevice.requireObject(resourceId: String, label: String): UiObject2 {
    return wait(Until.findObject(By.res(resourceId)), UI_TIMEOUT_MS)
        ?: error("$label did not become ready within ${UI_TIMEOUT_MS}ms")
}

private fun UiDevice.requireAllVisibleSearchVideosPlaying() {
    val pattern = Pattern.compile("${Pattern.quote(SEARCH_VIDEO_TAG_PREFIX)}.*")
    val visible = waitUntil(UI_TIMEOUT_MS) {
        findObjects(By.res(pattern))
            .filter(UiObject2::isVisibleToUser)
            .takeIf { videos ->
                videos.size >= 2 && videos.all(UiObject2::reportsPlaying)
            }
    } ?: error(
        "Search fixture did not expose at least two simultaneously visible playing videos " +
            "within ${UI_TIMEOUT_MS}ms",
    )
    check(visible.all(UiObject2::reportsPlaying)) {
        "A visible Search fixture video stopped autoplaying"
    }
}

private fun UiDevice.requireDurationWaiting() {
    val status = requireObject(DURATION_STATUS_TAG, "Duration benchmark status")
    check(status.contentDescription == "Waiting 0/24") {
        "Duration benchmark began before the measured start signal: ${status.contentDescription}"
    }
}

private fun UiDevice.requireDurationSettled() {
    val status = waitUntil(UI_TIMEOUT_MS) {
        findObject(By.res(DURATION_STATUS_TAG))
            ?.takeIf { node -> node.contentDescription == DURATION_SETTLED_DESCRIPTION }
    }
    checkNotNull(status) {
        "Duration benchmark did not settle all 24 decisions within ${UI_TIMEOUT_MS}ms"
    }
}

private fun UiDevice.requireViewerVideoPlaying(page: Int) {
    val expectedTag = "${VIEWER_VIDEO_TAG_PREFIX}$page"
    val video = waitUntil(UI_TIMEOUT_MS) {
        findObject(By.res(expectedTag))
            ?.takeIf { objectOnPage ->
                objectOnPage.isVisibleToUser() && objectOnPage.reportsPlaying()
            }
    }
    checkNotNull(video) {
        "Viewer fixture page $page did not become visible and playing within ${UI_TIMEOUT_MS}ms"
    }
}

private fun UiDevice.swipeViewer(next: Boolean) {
    val startX = if (next) displayWidth * 4 / 5 else displayWidth / 5
    val endX = if (next) displayWidth / 5 else displayWidth * 4 / 5
    val centerY = displayHeight / 2
    check(swipe(startX, centerY, endX, centerY, 24)) {
        "UI Automator could not dispatch the Viewer ${if (next) "next" else "previous"} swipe"
    }
}

private fun UiObject2.reportsPlaying(): Boolean {
    return contentDescription?.startsWith("Playing ") == true
}

private fun UiObject2.isVisibleToUser(): Boolean = visibleBounds.width() > 0 && visibleBounds.height() > 0

private inline fun <T> UiDevice.waitUntil(timeoutMs: Long, value: () -> T?): T? {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        value()?.let { return it }
        SystemClock.sleep(POLL_INTERVAL_MS)
    }
    return value()
}
