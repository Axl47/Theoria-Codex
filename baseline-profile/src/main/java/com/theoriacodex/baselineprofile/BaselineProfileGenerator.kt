package com.theoriacodex.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
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

    private companion object {
        const val PACKAGE_NAME = "com.theoriacodex"
        const val UI_TIMEOUT_MS = 15_000L
        val TOP_LEVEL_DESTINATIONS = listOf("Search", "Recents", "For You", "Codex", "Settings")
    }
}
