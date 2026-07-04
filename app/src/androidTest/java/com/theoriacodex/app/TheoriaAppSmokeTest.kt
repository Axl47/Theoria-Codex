package com.theoriacodex.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class TheoriaAppSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appShellRendersTopLevelNavigation() {
        composeRule.onNodeWithText("Search").assertIsDisplayed()
        composeRule.onNodeWithText("Recents").assertIsDisplayed()
        composeRule.onNodeWithText("For You").assertIsDisplayed()
        composeRule.onNodeWithText("Codex").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }
}
