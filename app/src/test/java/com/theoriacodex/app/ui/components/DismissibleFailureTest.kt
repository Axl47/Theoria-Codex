package com.theoriacodex.app.ui.components

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DismissibleFailureTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `dismiss hides the failure until a different one arrives`() {
        var message by mutableStateOf("Malformed JSON")
        compose.setContent {
            DismissibleFailure(message) { dismiss ->
                FeedErrorTile(message = message, onRetry = {}, onDismiss = dismiss)
            }
        }

        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Malformed JSON").assertDoesNotExist()

        message = "Timed out"
        compose.onNodeWithText("Timed out").assertIsDisplayed()
    }
}
