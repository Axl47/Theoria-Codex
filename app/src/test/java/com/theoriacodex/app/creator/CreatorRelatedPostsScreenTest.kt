package com.theoriacodex.app.creator

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.creator.state.CreatorAction
import com.theoriacodex.app.creator.state.CreatorUiState
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.begin
import com.theoriacodex.app.related.complete
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CreatorRelatedPostsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `hide liked retains the seed until the related shelf is dismissed`() {
        val seed = testPost(sourcePostId = "seed")
        val state = mutableStateOf(CreatorUiState(
            creator = CreatorProfile(SourceKey.PIXIV, "Artist", profileId = "artist"),
            queryHash = "creator:PIXIV:artist",
            results = listOf(seed),
        ))
        val liked = mutableStateOf(false)
        val pending = mutableStateOf(false)
        val actions = mutableListOf<CreatorAction>()
        compose.setContent {
            MaterialTheme {
                CreatorProfileScreen(
                    state = state.value,
                    likedPostIds = if (liked.value) setOf(seed.id) else emptySet(),
                    savedPostIds = emptySet(),
                    pendingLikePostIds = if (pending.value) setOf(seed.id) else emptySet(),
                    fabRestoreState = FeedFabRestoreState(hideLiked = true),
                    onToggleLike = { post ->
                        assertEquals(seed.id, post.id)
                        pending.value = true
                        liked.value = true
                    },
                    onAction = { action ->
                        actions += action
                        if (action == CreatorAction.DismissRelatedPosts) {
                            state.value = state.value.copy(relatedPosts = RelatedPostsUiState.Idle)
                        }
                    },
                    onRequestSaveToCodex = {},
                    onSaveToDevice = {},
                    onOpenUrl = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Like post").performClick()
        // Room membership may reach the grid before the commit callback starts the shelf.
        compose.onNodeWithContentDescription("Unlike post").assertIsDisplayed()
        compose.runOnIdle {
            val loading = RelatedPostsUiState.Idle.begin(seed, 0, 1)
            state.value = state.value.copy(
                relatedPosts = loading.complete(loading.request, emptyList(), setOf(seed.id)),
            )
            pending.value = false
        }
        compose.onNodeWithText("No new related posts").assertIsDisplayed()
        compose.onNodeWithContentDescription("Unlike post").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("No uploads match the current filters.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(CreatorAction.DismissRelatedPosts), actions) }
    }
}
