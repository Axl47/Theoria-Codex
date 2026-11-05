package com.theoriacodex.baselineprofile

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun topLevelNavigation() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
    ) {
        startActivityAndWait()
        TOP_LEVEL_DESTINATIONS.forEach { destination ->
            val navigationItem = device.wait(
                Until.findObject(By.desc(destination)),
                UI_TIMEOUT_MS,
            ) ?: error("Top-level destination '$destination' did not become available")
            navigationItem.click()
            device.waitForIdle()
        }
    }

    /** The paths users repeat most, driven through the offline fixture app so no provider is contacted. */
    @Test
    fun feedViewerAndSearchEditor() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
    ) {
        startActivityAndWait(fixtureJourney())
        val media = device.wait(Until.findObject(By.res(FEED_MEDIA)), UI_TIMEOUT_MS)
            ?: error("Fixture feed did not present media")
        repeat(FEED_FLINGS) { flingFeed(Direction.DOWN) }
        repeat(FEED_FLINGS) { flingFeed(Direction.UP) }
        (device.findObject(By.res(FEED_MEDIA)) ?: media).click()
        device.wait(Until.findObject(By.res(VIEWER_MEDIA)), UI_TIMEOUT_MS)
            ?: error("Viewer did not open from the fixture feed")
        repeat(VIEWER_SWIPES) {
            device.swipe(device.displayWidth * 4 / 5, device.displayHeight / 2, device.displayWidth / 5, device.displayHeight / 2, 24)
            device.waitForIdle()
        }
        device.pressBack()
        device.wait(Until.findObject(By.res(FEED_MEDIA)), UI_TIMEOUT_MS)
            ?: error("Viewer did not return to the fixture feed")
        (device.wait(Until.findObject(By.res(SEARCH_BAR)), UI_TIMEOUT_MS)
            ?: error("Search bar was unavailable")).click()
        device.waitForIdle()
        device.pressBack()
    }

    private fun MacrobenchmarkScope.flingFeed(direction: Direction) {
        val grid = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
            ?: error("Fixture feed has no scrollable grid")
        grid.setGestureMargin(device.displayWidth / 6)
        grid.fling(direction)
        device.waitForIdle()
    }

    private fun fixtureJourney() = Intent("com.theoriacodex.action.BENCHMARK_FIXTURE")
        .setComponent(ComponentName(PACKAGE_NAME, "com.theoriacodex.app.benchmark.BenchmarkFixtureActivity"))
        .putExtra("benchmark_scenario", "journey_mixed")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    private companion object {
        const val PACKAGE_NAME = "com.theoriacodex.baselineprofile"
        const val FEED_FLINGS = 3
        const val VIEWER_SWIPES = 3
        const val SEARCH_BAR = "Search bar"
        val FEED_MEDIA: Pattern = Pattern.compile("search_media_pixiv_benchmark_video_.*")
        val VIEWER_MEDIA: Pattern = Pattern.compile("viewer_video_pixiv_benchmark_video_.*")
        const val UI_TIMEOUT_MS = 15_000L
        val TOP_LEVEL_DESTINATIONS = listOf("Search", "Recents", "For You", "Codex", "Settings")
    }
}
