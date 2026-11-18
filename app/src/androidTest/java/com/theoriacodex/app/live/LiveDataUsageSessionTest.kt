package com.theoriacodex.app.live

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.app.diagnostics.NetworkUsage
import com.theoriacodex.app.media.MediaCacheMaintenance
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A fixed browsing session against the installed Debug app's real accounts and live feeds, reporting what it
 * downloaded. It first clears disposable media caches, as Settings does, so every run starts from the same state
 * (`theoriaKeepCaches=true` keeps them). Feeds change between runs, so compare medians of several runs per build.
 * Excluded from the default lane; `scripts/real_data_device.sh data-usage` passes `theoriaLiveDataUsage=true`.
 * It drives the app only through accessibility nodes and never captures the screen.
 */
@RunWith(AndroidJUnit4::class)
class LiveDataUsageSessionTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun requireExplicitOptIn() {
        val enabled = InstrumentationRegistry.getArguments().getString(OPT_IN_ARGUMENT)?.toBooleanStrictOrNull() ?: false
        assumeTrue("The live data-usage session requires $OPT_IN_ARGUMENT=true", enabled)
    }

    @Test
    fun browseLiveFeedsAndReportDownloads() {
        val keepCaches = InstrumentationRegistry.getArguments().getString(KEEP_CACHES_ARGUMENT)?.toBooleanStrictOrNull() ?: false
        if (!keepCaches) clearDisposableMediaCaches()
        val started = SystemClock.elapsedRealtime()
        NetworkUsage.reset()
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        requireNotNull(device.wait(Until.findObject(By.desc(SEARCH)), LAUNCH_TIMEOUT_MS)) { "The app shell did not open" }
        settle(FEED_SETTLE_MS)

        browse(FOR_YOU)
        browse(SEARCH)
        val viewerOpened = openViewerAndSwipe()

        val report = NetworkUsage.snapshot()
            .put("sessionMs", SystemClock.elapsedRealtime() - started)
            .put("viewerOpened", viewerOpened)
            .put("cachesCleared", !keepCaches)
            .put("metered", context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered)
            .put("steps", JSONObject().put("feedFlings", FEED_FLINGS).put("viewerSwipes", VIEWER_SWIPES))
        Log.i(LOG_TAG, report.toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(REPORT_KEY, report.toString()) })
    }

    private fun clearDisposableMediaCaches() = runBlocking {
        val container = (context as TheoriaAppContainerOwner).awaitAppContainer()
        MediaCacheMaintenance(context, container.data.cacheRepository, container.sources.pixivUgoiraClient).clear()
    }

    private fun browse(destination: String) {
        requireNotNull(device.wait(Until.findObject(By.desc(destination)), STEP_TIMEOUT_MS)) { "Missing $destination tab" }.click()
        settle(FEED_SETTLE_MS)
        repeat(FEED_FLINGS) {
            largestScrollable()?.apply { setGestureMargin(device.displayWidth / 6) }?.fling(Direction.DOWN)
            settle(FLING_SETTLE_MS)
        }
    }

    /** Opens the largest visible card (cards are long-clickable too); the Viewer hides the bottom navigation. */
    private fun openViewerAndSwipe(): Boolean {
        val card = device.findObjects(By.clickable(true).longClickable(true))
            ?.maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }
            ?: return false
        card.click()
        settle(VIEWER_SETTLE_MS)
        if (device.findObject(By.desc(SEARCH)) != null) return false
        repeat(VIEWER_SWIPES) {
            val y = device.displayHeight / 2
            device.swipe(device.displayWidth * 4 / 5, y, device.displayWidth / 5, y, SWIPE_STEPS)
            settle(VIEWER_SETTLE_MS)
        }
        device.pressBack()
        settle(FLING_SETTLE_MS)
        return true
    }

    private fun largestScrollable(): UiObject2? =
        device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() * it.visibleBounds.width() }

    private fun settle(ms: Long) {
        device.waitForIdle()
        SystemClock.sleep(ms)
    }

    private companion object {
        const val OPT_IN_ARGUMENT = "theoriaLiveDataUsage"
        const val KEEP_CACHES_ARGUMENT = "theoriaKeepCaches"
        const val REPORT_KEY = "theoriaDataUsage"
        const val LOG_TAG = "TheoriaDataUsage"
        const val SEARCH = "Search"
        const val FOR_YOU = "For You"
        const val FEED_FLINGS = 6
        const val VIEWER_SWIPES = 5
        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val STEP_TIMEOUT_MS = 10_000L
        const val FEED_SETTLE_MS = 6_000L
        const val FLING_SETTLE_MS = 3_000L
        const val VIEWER_SETTLE_MS = 4_000L
        const val SWIPE_STEPS = 24
    }
}
