package com.theoriacodex.app.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.related.LocalRelatedPostsSupport
import com.theoriacodex.app.testing.testPost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PostActionSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `search terms open in place while other actions close the menu`() {
        var dismissals = 0
        var deviceSaves = 0
        compose.setContent {
            MaterialTheme {
                PostActionSheet(
                    post = testPost(),
                    onDismiss = { dismissals++ },
                    onSaveToDevice = { deviceSaves++ },
                    tagContent = { Text("Tag list") },
                )
            }
        }

        compose.onNodeWithText("Tag list").assertDoesNotExist()
        compose.onNodeWithText("Save to device").performClick()
        compose.runOnIdle { assertEquals(1 to 1, dismissals to deviceSaves) }

        compose.onNodeWithText("Search terms").performClick()
        compose.onNodeWithText("Tag list").assertIsDisplayed()
        compose.onNodeWithText("Save to device").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun `more like this appears only for a source that can answer it`() {
        var supported by mutableStateOf(false)
        var shown = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalRelatedPostsSupport provides { supported }) {
                    PostActionSheet(
                        post = testPost(),
                        onDismiss = {},
                        onSaveToDevice = {},
                        onShowRelated = { shown++ },
                        tagContent = {},
                    )
                }
            }
        }

        compose.onNodeWithText("More like this").assertDoesNotExist()
        supported = true
        compose.onNodeWithText("More like this").performClick()
        compose.runOnIdle { assertEquals(1, shown) }
    }

    @Test fun `offers the Viewer's like and link actions the post supports`() {
        val opened = mutableListOf<String>()
        var liked by mutableStateOf(false)
        var dismissals = 0
        val links = PostLinkActions(
            openOriginalPost = { opened += "original $it" },
            openInBrowser = { opened += "browser $it" },
            findOnOtherSites = { post -> { opened += "find ${post.id.sourcePostId}" } },
        )
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPostLinkActions provides links) {
                    PostActionSheet(
                        post = testPost(pageUrl = "https://example.com/p").copy(originUrl = "https://origin.test/1"),
                        onDismiss = { dismissals++ },
                        onSaveToDevice = {},
                        liked = liked,
                        onToggleLike = { liked = !liked },
                        tagContent = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Like").performClick()
        compose.onNodeWithText("Unlike").assertIsDisplayed()
        compose.onNodeWithText("Open original post").performClick()
        compose.onNodeWithText("Find on other sites").performClick()
        compose.onNodeWithText("Open in browser").performClick()
        compose.runOnIdle {
            assertEquals(listOf("original https://origin.test/1", "find post-1", "browser https://example.com/p"), opened)
            assertEquals(4, dismissals)
        }
    }

    @Test fun `hides link actions the post cannot use`() {
        compose.setContent {
            MaterialTheme {
                PostActionSheet(post = testPost(pageUrl = null), onDismiss = {}, onSaveToDevice = {}, tagContent = {})
            }
        }

        listOf("Like", "Open original post", "Find on other sites", "Open in browser").forEach { label ->
            compose.onNodeWithText(label).assertDoesNotExist()
        }
    }
}
