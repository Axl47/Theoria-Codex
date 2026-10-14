package com.theoriacodex.app.related

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.components.RelatedPostsShelf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RelatedPostsShelfTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `empty loading and failed second pages keep the previous page reachable`() {
        val firstLoading = RelatedPostsUiState.Idle.begin(testPost(sourcePostId = "seed"), 0, 1)
        val sibling = testPost(sourcePostId = "sibling")
        val first = firstLoading.complete(firstLoading.request, listOf(sibling), emptySet())
        val secondLoading = first.begin(
            testPost(sourcePostId = "promoted"), 1, 2, retainedPages = first.availablePages,
        )
        val empty = secondLoading.complete(secondLoading.request, emptyList(), emptySet())
        val failed = secondLoading.fail(secondLoading.request, "Could not load related posts")
        val state = mutableStateOf(empty)
        compose.setContent {
            MaterialTheme {
                RelatedPostsShelf(
                    state = state.value,
                    // Filtered cards keep this navigation test independent of media loading.
                    posts = emptyList(),
                    likedPostIds = emptySet(),
                    pixivUgoiraClient = null,
                    onToggleLike = {},
                    onOpenPost = { _, _ -> },
                    onLongPress = {},
                    onDismiss = {},
                    onRetry = {},
                    onPreviousPage = {
                        state.value = state.value.selectPage(state.value.currentPageIndex - 1)
                    },
                    onNextPage = {},
                    onViewportChanged = { _, _ -> },
                    onAuthoritativeDurationKnown = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("No new related posts").assertIsDisplayed()
        for (second in listOf(empty, secondLoading, failed)) {
            compose.runOnIdle { state.value = second }
            compose.onNodeWithContentDescription("Next related page").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Previous related page")
                .assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(0, state.value.currentPageIndex)
                assertEquals(listOf(sibling), state.value.loadedPosts)
            }
            compose.onNodeWithContentDescription("Previous related page").assertDoesNotExist()
        }
    }
}
