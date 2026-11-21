package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CodexCleaningUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `collection action selects multiple comparisons while excluding target and removed choices`() {
        val likesId = ProfileLibraryIds.likes("profile-main")
        val codices = mutableStateOf(listOf(Codex("target", "Collection", 1), Codex("a", "Source A", 2), Codex("b", "Source B", 3)))
        var submitted: Pair<String, Set<String>>? = null
        compose.setContent { MaterialTheme {
            CodexListScreen(
                codices = codices.value, itemCounts = emptyMap(), codexCoverCandidates = emptyMap(),
                observeActionOptions = { flowOf(CodexActionOptions()) }, onOpenCodex = {}, onImportCodex = {},
                onDownloadCodex = {}, onShareCodex = {}, onFilterByTag = {}, onCommitReorder = {},
                onCreateCodex = {}, onRenameCodex = { _, _ -> }, onSetAutomaticTag = { _, _, _ -> }, onDeleteCodex = {},
                likesCodexId = likesId, onCleanCodex = { id, sources -> submitted = id to sources },
            )
        } }
        compose.onNodeWithText("Collection").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("Clean").performClick()
        compose.onNodeWithText("Clean Collection").assertIsDisplayed()
        compose.onNode(hasText("Collection") and isToggleable()).assertDoesNotExist()
        compose.onNodeWithText("Matching posts will also be unliked.").assertDoesNotExist()
        compose.onNodeWithText("Clean Codex").assertIsNotEnabled()
        compose.onNode(hasText("Source A") and isToggleable()).performClick()
        compose.onNode(hasText("Source B") and isToggleable()).performClick()
        compose.onNodeWithText("Clean Codex").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("target" to setOf("a", "b"), submitted) }

        compose.onNodeWithText("Collection").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("Clean").performClick()
        compose.onNodeWithText("Clean Codex").assertIsNotEnabled()
        compose.onNode(hasText("Source A") and isToggleable()).performClick()
        compose.runOnIdle { codices.value = codices.value.filterNot { it.codexId == "a" }; submitted = null }
        compose.onNodeWithText("Clean Codex").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(submitted) }
    }

    @Test fun `cleaning Likes warns about unliking and excludes itself from comparisons`() {
        val likes = Codex(ProfileLibraryIds.likes("profile-main"), "Likes", 1)
        var submitted: Set<String>? = null
        compose.setContent { MaterialTheme {
            CodexCleaningSheet(
                target = likes,
                codices = listOf(likes, Codex("a", "Source A", 2)),
                isLikesCodex = true,
                matches = mapOf("a" to setOf(PostId(SourceKey.PIXIV, "1"), PostId(SourceKey.PIXIV, "2"))),
                onDismiss = {},
                onClean = { submitted = it },
            )
        } }
        compose.onNodeWithText("Clean Likes").assertIsDisplayed()
        compose.onNodeWithText("Matching posts will also be unliked.").assertIsDisplayed()
        compose.onNode(hasText("Likes") and isToggleable()).assertDoesNotExist()
        compose.onNodeWithText("Clean Codex").assertIsNotEnabled()
        compose.onNode(hasText("Source A") and isToggleable()).performClick()
        compose.onNodeWithText("Remove 2 posts").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(setOf("a"), submitted) }
    }

    @Test fun `the removal count counts a post held by several comparisons once`() {
        val shared = PostId(SourceKey.PIXIV, "1")
        val matches = mapOf("a" to setOf(shared), "b" to setOf(shared, PostId(SourceKey.PIXIV, "2")))
        assertEquals(2, codexCleaningRemovalCount(setOf("a", "b"), matches))
        assertEquals(null, codexCleaningRemovalCount(setOf("a", "c"), matches))
        assertEquals(null, codexCleaningRemovalCount(emptySet(), matches))
    }

    @Test fun `Review hands the chosen comparisons to the deck instead of removing`() {
        val target = Codex("target", "Collection", 1)
        var reviewed: Set<String>? = null
        var cleaned = false
        compose.setContent { MaterialTheme {
            CodexCleaningSheet(
                target = target,
                codices = listOf(target, Codex("a", "Source A", 2), Codex("b", "Source B", 3)),
                isLikesCodex = false,
                matches = mapOf("a" to setOf(PostId(SourceKey.PIXIV, "1")), "b" to emptySet()),
                onDismiss = {},
                onClean = { cleaned = true },
                onReview = { reviewed = it },
            )
        } }
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithText("Review").assertIsNotEnabled()
        compose.onNode(hasText("Source B") and isToggleable()).performClick()
        compose.onNodeWithText("Review").assertIsNotEnabled()
        compose.onNode(hasText("Source A") and isToggleable()).performClick()
        compose.onNodeWithText("Review").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(setOf("a", "b") to false, reviewed to cleaned) }
    }

    @Test fun `the deck reviews the target's posts any chosen comparison holds, in the target's order`() {
        val posts = (1..4).map { testPost(sourcePostId = "p$it") }
        val inA = listOf(CodexItem("a", posts[3].id, 1L), CodexItem("a", posts[1].id, 2L))
        val inB = listOf(CodexItem("b", posts[1].id, 3L), CodexItem("b", PostId(SourceKey.PIXIV, "elsewhere"), 4L))
        assertEquals(listOf(posts[1], posts[3]), codexCleaningReviewPosts(posts, listOf(inA, inB)))
        assertEquals(emptyList<Post>(), codexCleaningReviewPosts(posts, emptyList()))
    }
}
