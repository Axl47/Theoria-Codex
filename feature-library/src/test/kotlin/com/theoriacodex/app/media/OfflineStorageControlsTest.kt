package com.theoriacodex.app.media

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.data.repository.OfflineMediaSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OfflineStorageControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `the storage sheet clears cache and retries a breakdown that could not be read`() {
        var cacheClears = 0
        var refreshes = 0
        compose.setContent {
            MaterialTheme {
                StorageSheet(
                    OfflineStorageUiState(isOpen = true, offline = OfflineMediaSnapshot(bytes = 1_024)),
                    onDismiss = {}, onRefresh = { refreshes++ }, onClearDisposable = { cacheClears++ },
                )
            }
        }
        compose.onNodeWithText("Clear disposable cache").assertIsEnabled().performClick()
        compose.onNodeWithText("Storage breakdown unavailable").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, cacheClears); assertEquals(1, refreshes) }
    }
}
