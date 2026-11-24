package com.theoriacodex.app.codex

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.domain.model.ImageRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CodexEditModeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `the checkmark selects its post and Back leaves selection before the Codex`() {
        val media = ImageRef(null, "/missing/post.jpg", "image/jpeg")
        val post = testPost(sourcePostId = "saved").copy(preview = media, full = media, media = listOf(media))
        var destinationBacks = 0
        compose.setContent {
            BackHandler { destinationBacks++ }
            MaterialTheme {
                CodexDetailScreen(
                    codexId = "mine",
                    codexName = "Mine", posts = listOf(post), sortMode = CodexSortMode.NEWEST_SAVED,
                    availableSources = emptySet(), creatorBrowsingSources = emptySet(),
                    onOpenViewer = { _, _ -> }, onAddPostsToAnotherCodex = {}, onRemovePosts = {}, onSavePostToDevice = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Select posts").performClick()
        compose.onNodeWithContentDescription("Not selected").performClick()
        compose.onNodeWithContentDescription("Selected").assertExists()
        compose.onNodeWithText("1/1").assertExists()

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Select posts").assertExists()
        compose.runOnIdle { assertEquals(0, destinationBacks) }

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1, destinationBacks) }
    }
}
