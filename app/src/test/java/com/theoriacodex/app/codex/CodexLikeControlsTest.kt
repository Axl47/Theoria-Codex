package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
class CodexLikeControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `virtual feed heart uses shared membership and local toggle callback`() {
        val media = ImageRef(null, "/missing/post.jpg", "image/jpeg")
        val post = testPost(sourcePostId = "virtual").copy(preview = media, full = media, media = listOf(media))
        val liked = mutableStateOf(false)
        var toggles = 0
        compose.setContent {
            MaterialTheme {
                CodexDetailScreen(
                    codexName = "Searches", posts = listOf(post), sortMode = CodexSortMode.NEWEST_SAVED,
                    availableSources = emptySet(), creatorBrowsingSources = emptySet(),
                    onOpenViewer = { _, _ -> }, onAddPostsToAnotherCodex = {}, onRemovePosts = {}, onSavePostToDevice = {},
                    onOpenCreatorProfile = {}, onOpenLegacyCreatorProfile = {}, onBack = {}, onDeleteCodex = {}, isLikesCodex = false,
                    likedPostIds = if (liked.value) setOf(post.id) else emptySet(),
                    onToggleLike = { assertEquals(post.id, it.id); toggles++; liked.value = !liked.value },
                )
            }
        }
        compose.onNodeWithContentDescription("Like post").performClick()
        compose.onNodeWithContentDescription("Unlike post").performClick()
        compose.runOnIdle { assertEquals(2, toggles) }
    }
}
