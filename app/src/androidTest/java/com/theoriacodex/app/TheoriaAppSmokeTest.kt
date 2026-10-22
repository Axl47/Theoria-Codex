package com.theoriacodex.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TheoriaAppSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appShellRendersTopLevelNavigation() {
        assertEquals(
            "MainActivity must be RESUMED; an asleep or securely locked device stops the " +
                "activity and removes its Compose semantics hierarchy",
            Lifecycle.State.RESUMED,
            composeRule.activityRule.scenario.state,
        )

        // The app container and startup workflow are initialized asynchronously after the
        // Activity reaches RESUMED. Wait for visible shell navigation instead of racing the
        // startup surface on a cold CI emulator.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onNodeWithContentDescription("Search").isDisplayed()
        }
        composeRule.onNodeWithContentDescription("Search").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Recents").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("For You").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Codex").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }
}
