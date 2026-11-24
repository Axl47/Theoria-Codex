package com.theoriacodex.app.codex

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.testing.FEED_POST_CARD
import com.theoriacodex.app.testing.countCompositions
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
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

    private fun post(id: String): Post {
        val media = ImageRef(null, "/missing/$id.jpg", "image/jpeg")
        return testPost(sourcePostId = id).copy(preview = media, full = media, media = listOf(media))
    }

    private fun render(posts: List<Post>, onDestinationBack: () -> Unit = {}) {
        compose.setContent {
            BackHandler(onBack = onDestinationBack)
            MaterialTheme {
                CodexDetailScreen(
                    codexId = "mine",
                    codexName = "Mine", posts = posts, sortMode = CodexSortMode.NEWEST_SAVED,
                    availableSources = emptySet(),
                    onOpenViewer = { _, _ -> }, onAddPostsToAnotherCodex = {}, onRemovePosts = {}, onSavePostToDevice = {},
                    onBack = {},
                )
            }
        }
    }

    @Test fun `the checkmark selects its post and Back leaves selection before the Codex`() {
        var destinationBacks = 0
        render(listOf(post("saved"))) { destinationBacks++ }
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

    @Test fun `selecting a post recomposes only its own cell`() = countCompositions { counter ->
        render((0 until 4).map { post("saved-$it") })
        compose.onNodeWithContentDescription("Select posts").performClick()
        compose.waitForIdle()
        counter.reset()
        compose.onAllNodesWithContentDescription("Not selected")[0].performClick()
        compose.onNodeWithContentDescription("Selected").assertExists()
        assertEquals("selection cells recomposed", 1, counter.count("com.theoriacodex.app.codex.CodexSelectablePostCard"))
        assertEquals("cards recomposed", 0, counter.count(FEED_POST_CARD))
    }
}
