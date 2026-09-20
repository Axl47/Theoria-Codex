package com.theoriacodex.app.media

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.theoriacodex.data.repository.OfflineMediaSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OfflineStorageControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `offline removal requires its own explicit confirmation and cache clearing stays separate`() {
        val state = mutableStateOf(OfflineStorageUiState(
            isOpen = true, offline = OfflineMediaSnapshot(postCount = 1, mediaCount = 2, bytes = 1_024),
            owners = listOf(OfflineStorageOwner("codex:a", "Favorites", 1)),
            disposable = DisposableMediaCacheSnapshot(imageBytes = 512),
        ))
        var offlineRemovals = 0
        var cacheClears = 0
        compose.setContent {
            MaterialTheme {
                OfflineStorageSheet(
                    state.value, onDismiss = {}, onRetry = {}, onCancel = {},
                    onRemove = { state.value = state.value.copy(pendingRemoval = OfflineRemovalTarget.Owner(it, "Favorites")) },
                    onClearOffline = { state.value = state.value.copy(pendingRemoval = OfflineRemovalTarget.All) },
                    onConfirmRemoval = { offlineRemovals++; state.value = state.value.copy(pendingRemoval = null) },
                    onDismissRemoval = { state.value = state.value.copy(pendingRemoval = null) },
                    onClearDisposable = { cacheClears++ },
                )
            }
        }
        compose.onNodeWithText("Clear disposable cache").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, cacheClears); assertEquals(0, offlineRemovals) }
        compose.onNodeWithText("Remove all").performScrollTo().performClick()
        compose.onNodeWithText("Remove all offline copies?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, offlineRemovals) }
        compose.onNodeWithTag("confirm-offline-removal").performClick()
        compose.runOnIdle { assertEquals(1, offlineRemovals); assertEquals(1, cacheClears) }
        compose.runOnIdle {
            state.value = state.value.copy(offline = OfflineMediaSnapshot(bytes = 1_024), owners = emptyList())
        }
        compose.onNodeWithText("Remove all").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithText("Remove all offline copies?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, offlineRemovals) }
        compose.onNodeWithTag("confirm-offline-removal").performClick()
        compose.runOnIdle { assertEquals(2, offlineRemovals); assertEquals(1, cacheClears) }
    }
}
