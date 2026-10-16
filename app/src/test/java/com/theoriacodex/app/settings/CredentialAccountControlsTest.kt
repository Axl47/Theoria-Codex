package com.theoriacodex.app.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CredentialAccountControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `configured account hides editing until disconnect and failed save keeps editing`() {
        val configured = mutableStateOf(true)
        val status = mutableStateOf("Configured")
        var saveRequested = false
        compose.setContent {
            MaterialTheme {
                Column {
                    CredentialAccountControls(
                        name = "Gelbooru", statusLabel = status.value, configured = configured.value,
                        userIdInput = "42", apiKeyInput = "", enabled = true,
                        onUserIdChange = {}, onApiKeyChange = {},
                        onSave = { saveRequested = true; status.value = "Missing user ID or API key" },
                        onDisconnect = { configured.value = false; status.value = "Not configured" },
                    )
                }
            }
        }
        compose.onNodeWithText("User ID").assertDoesNotExist()
        compose.onNodeWithText("Replacement API Key").assertDoesNotExist()
        compose.onNodeWithText("Save", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithText("User ID").assertExists()
        compose.onNodeWithText("Replacement API Key").assertExists()
        compose.onNodeWithText("Save", substring = false).performClick()
        assertTrue(saveRequested)
        compose.onNodeWithText("Missing user ID or API key").assertExists()
        compose.onNodeWithText("User ID").assertExists()
        compose.onNodeWithText("Replacement API Key").assertExists()
        configured.value = true
        status.value = "Configured"
        compose.onNodeWithText("User ID").assertDoesNotExist()
        compose.onNodeWithText("Replacement API Key").assertDoesNotExist()
        compose.onNodeWithText("Disconnect").assertExists()
    }
}
