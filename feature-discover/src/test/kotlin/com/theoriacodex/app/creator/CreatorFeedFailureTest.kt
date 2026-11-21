package com.theoriacodex.app.creator

import com.theoriacodex.app.related.testRelatedShelf
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.theoriacodex.app.creator.state.CreatorUiState
import com.theoriacodex.app.ui.LocalNetworkOnline
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CreatorFeedFailureTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a failed creator page says when the device is offline`() {
        compose.setContent {
            CompositionLocalProvider(LocalNetworkOnline provides MutableStateFlow(false)) {
                MaterialTheme {
                    CreatorProfileScreen(
                        state = CreatorUiState(
                            creator = CreatorProfile(SourceKey.PIXIV, "Artist", profileId = "artist"),
                            queryHash = "creator:PIXIV:artist",
                            errorMessage = "Pixiv could not be reached.",
                        ),
                        related = testRelatedShelf(),
                        likedPostIds = emptySet(),
                        savedPostIds = emptySet(),
                        onToggleLike = {},
                        onAction = {},
                        onRequestSaveToCodex = {},
                        onSaveToDevice = {},
                        onOpenUrl = {},
                    )
                }
            }
        }
        compose.onNodeWithText("You're offline").assertIsDisplayed()
        // Creator pages do not retry on reconnect, so the tile keeps the failure rather than promise one.
        compose.onNodeWithText("Pixiv could not be reached.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }
}
