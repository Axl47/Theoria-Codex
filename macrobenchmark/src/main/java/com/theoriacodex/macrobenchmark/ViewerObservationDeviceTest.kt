package com.theoriacodex.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Device-only observation regression: real overlay, fitted pixels, identity and route departure. */
@RunWith(AndroidJUnit4::class)
class ViewerObservationDeviceTest {
    @Test
    fun observesVideoAfterControlsHideAndRejectsDepartedOrDifferentMedia() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.context.startActivity(Intent("com.theoriacodex.action.BENCHMARK_FIXTURE")
            .setComponent(ComponentName("com.theoriacodex.benchmark",
                "com.theoriacodex.app.benchmark.BenchmarkFixtureActivity"))
            .putExtra("benchmark_scenario", "journey_mixed")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            val video = device.wait(Until.findObject(By.res(Pattern.compile("search_media_pixiv_benchmark_video_.*"))), 15_000)
                ?: error("Search video unavailable")
            val identity = "viewer_video_${video.resourceName.removePrefix("search_media_")}"
            video.click()
            check(device.wait(Until.hasObject(By.desc("Pause")), 15_000)) { "Viewer controls never appeared" }
            device.requireMovingViewer(identity, "Viewer with controls")
            check(device.wait(Until.gone(By.desc("Pause")), 15_000)) { "Viewer controls did not auto-hide" }
            val bounds = viewerPreviewSnapshot(identity).getValue(identity)
            check(bounds.width() > device.displayWidth / 2 && bounds.height() > device.displayHeight / 8)
            check(kotlin.math.abs(bounds.width().toFloat() / bounds.height() - 16f / 9f) < 0.05f) {
                "Observation must use the fitted 16:9 fixture image, not the controls footer: $bounds"
            }
            device.requireMovingViewer(identity, "Viewer after controls hide")
            check(viewerPreviewSnapshot("${identity}_wrong").isEmpty()) { "Observation accepted another canonical post" }
            device.pressBack()
            check(device.wait(Until.hasObject(By.res(Pattern.compile("search_media_pixiv_benchmark_video_.*"))), 15_000))
            check(viewerPreviewSnapshot(identity).isEmpty()) { "Departed Viewer retained a surface observation" }
        } finally {
            device.executeShellCommand("am force-stop com.theoriacodex.benchmark")
        }
    }
}
