package com.theoriacodex.app.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.theoriacodex.app.related.LocalRelatedPostsSupport
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.PostId
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

    @Test fun `offers the Viewer's like, share and link actions the post supports`() {
        val opened = mutableListOf<String>()
        val liked = mutableStateOf(emptySet<PostId>())
        var dismissals = 0
        val actions = PostMenuActions(
            openOriginalPost = { opened += "original $it" },
            openInBrowser = { opened += "browser $it" },
            findOnOtherSites = { post -> { opened += "find ${post.id.sourcePostId}" } },
            share = { opened += "share $it" },
            copyTags = { post -> opened += "tags ${post.id.sourcePostId}" },
            copyLink = { opened += "copy $it" },
            likedPostIds = liked,
            toggleLike = { post -> liked.value += post.id },
        )
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPostMenuActions provides actions) {
                    PostActionSheet(
                        post = testPost(pageUrl = "https://example.com/p").copy(originUrl = "https://origin.test/1"),
                        onDismiss = { dismissals++ },
                        tagContent = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Like").performClick()
        compose.onNodeWithText("Unlike").assertIsDisplayed()
        compose.runOnIdle { assertEquals(setOf(testPost().id), liked.value) }
        val labels = listOf("Share", "Open original post", "Find on other sites", "Open in browser", "Copy tags", "Copy link")
        labels.forEach { label -> compose.onNodeWithText(label).performScrollTo().performClick() }
        compose.runOnIdle {
            assertEquals(
                listOf(
                    "share https://example.com/p", "original https://origin.test/1", "find post-1",
                    "browser https://example.com/p", "tags post-1", "copy https://example.com/p",
                ),
                opened,
            )
            assertEquals(labels.size + 1, dismissals)
        }
    }

    @Test fun `hides actions the post cannot use`() {
        compose.setContent {
            MaterialTheme {
                PostActionSheet(post = testPost(pageUrl = null), onDismiss = {}, tagContent = {})
            }
        }

        compose.onNodeWithText("Copy tags").assertExists()
        listOf("Share", "Open original post", "Find on other sites", "Open in browser", "Copy link").forEach { label ->
            compose.onNodeWithText(label).assertDoesNotExist()
        }
    }

    @Test fun `a like changes the menu's row without recomposing the route that holds it`() {
        val post = testPost()
        val liked = mutableStateOf(emptySet<PostId>())
        var routeCompositions = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPostMenuActions provides PostMenuActions(likedPostIds = liked)) {
                    SideEffect { routeCompositions++ }
                    PostActionSheet(post = post, onDismiss = {}, tagContent = {})
                }
            }
        }

        compose.onNodeWithText("Like").assertIsDisplayed()
        val before = compose.runOnIdle { routeCompositions }
        liked.value = setOf(post.id)
        compose.onNodeWithText("Unlike").assertIsDisplayed()
        compose.runOnIdle { assertEquals(before, routeCompositions) }
    }
}
