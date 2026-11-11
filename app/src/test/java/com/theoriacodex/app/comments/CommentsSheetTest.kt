package com.theoriacodex.app.comments

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.tags.PostTagSheetExtras
import com.theoriacodex.app.tags.tagList
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommentsSheetTest {
    @get:Rule val compose = createComposeRule()

    private val post: Post = testPost(
        taxonomy = listOf(
            PostTaxonomyTerm("painter", facet = SearchFacet.ARTIST),
            PostTaxonomyTerm("sunset"),
            PostTaxonomyTerm("sea"),
        ),
    )
    private val calls = mutableListOf<String>()
    private val actions = object : PostCommentsActions {
        override fun loadMore(post: Post) { calls += "more" }
        override fun toggleReplies(post: Post, comment: PostComment) { calls += "toggle:${comment.id}" }
        override fun loadMoreReplies(post: Post, comment: PostComment) { calls += "moreReplies:${comment.id}" }
    }

    private fun loaded(vararg ids: String, next: String? = null, replies: Map<String, CommentList> = emptyMap(), open: Set<String> = emptySet()) =
        PostCommentsState(
            top = CommentList().withPage(
                CommentPage(ids.map { PostComment(it, "author-$it", null, "text-$it", null, hasReplies = it == "a") }, null, next),
            ),
            replies = replies,
            expandedReplies = open,
        )

    private fun show(
        state: PostCommentsState?,
        collapsed: Boolean = state != null,
        scrollToComments: Boolean = false,
        headerHeight: Int = 40,
    ) = compose.setContent {
        MaterialTheme {
            Box(Modifier.size(360.dp, 480.dp)) {
                PostTagActionSection(
                    post = post,
                    header = { Box(Modifier.size(360.dp, headerHeight.dp)) { Text("Actions") } },
                    onAddIncludeTerm = { true },
                    onAddExcludeTerm = { true },
                    onRemoveIncludeTerm = {},
                    onRemoveExcludeTerm = {},
                    draftQuery = com.theoriacodex.app.search.state.emptySearchQuery(),
                    sheetExtras = PostTagSheetExtras(
                        tagsInitiallyCollapsed = collapsed,
                        scrollToAfterTags = scrollToComments,
                        afterTags = { state?.let { commentsSection(post, it, commentCount(null, it), actions, nowEpochMs = 0L) } },
                    ),
                )
            }
        }
    }

    @Test
    fun `tags fold to a peek that the header toggles, and the comments follow`() {
        show(loaded("a", "b"))

        compose.onNodeWithText("Comments").assertIsDisplayed()
        compose.onNodeWithText("author-a").assertIsDisplayed()
        // Folded: the peek shows chips, but no group headings.
        compose.onNodeWithText("Artists").assertDoesNotExist()
        compose.onNode(hasText("sunset") and hasClickAction()).assertExists()

        compose.onNode(hasText("Tags") and hasClickAction()).performClick()
        compose.onNodeWithText("Artists").assertIsDisplayed()
        compose.onNode(hasText("Tags") and hasClickAction()).performClick()
        compose.onNodeWithText("Artists").assertDoesNotExist()
    }

    @Test
    fun `a post without comments starts expanded and its collapse state ignores comments that arrive later`() {
        var state by mutableStateOf<PostCommentsState?>(null)
        var collapsed by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 480.dp)) {
                    PostTagActionSection(
                        post = post,
                        onAddIncludeTerm = { true }, onAddExcludeTerm = { true }, onRemoveIncludeTerm = {}, onRemoveExcludeTerm = {},
                        draftQuery = com.theoriacodex.app.search.state.emptySearchQuery(),
                        sheetExtras = PostTagSheetExtras(
                            tagsInitiallyCollapsed = collapsed,
                            afterTags = { state?.let { commentsSection(post, it, commentCount(null, it), actions, nowEpochMs = 0L) } },
                        ),
                    )
                }
            }
        }
        compose.onNodeWithText("Artists").assertIsDisplayed()

        state = loaded("a")
        collapsed = true
        compose.waitForIdle()

        compose.onNodeWithText("Comments").assertExists()
        compose.onNodeWithText("Artists").assertIsDisplayed()
    }

    @Test
    fun `opening for comments scrolls past a tall header`() {
        show(loaded("a", "b"), scrollToComments = true, headerHeight = 400)
        compose.onNodeWithText("Comments").assertIsDisplayed()
        compose.onNodeWithText("Actions").assertIsNotDisplayedOrMissing()
    }

    @Test
    fun `opening normally starts at the top`() {
        show(loaded("a", "b"), headerHeight = 400)
        compose.onNodeWithText("Actions").assertIsDisplayed()
        compose.onNodeWithText("Comments").assertDoesNotExist()
    }

    @Test
    fun `each comment shows its author and text, and its reply and more buttons ask the controller`() {
        show(loaded("a", "b", next = "p2"))

        compose.tagList().performScrollToNode(hasText("text-b"))
        compose.onNodeWithText("text-b").assertIsDisplayed()
        compose.onNodeWithText("Replies").performClick()
        compose.tagList().performScrollToNode(hasText("More comments"))
        compose.onNodeWithText("More comments").performClick()

        assertEquals(listOf("toggle:a", "more"), calls)
    }

    @Test
    fun `opened replies sit under their comment with their own more button`() {
        val replies = mapOf("a" to CommentList().withPage(CommentPage(listOf(PostComment("r1", "replier", null, "reply-text", null)), 2, "r2")))
        show(loaded("a", "b", replies = replies, open = setOf("a")))

        compose.onNodeWithText("Hide replies").assertExists()
        compose.tagList().performScrollToNode(hasText("reply-text"))
        compose.onNodeWithText("reply-text").assertIsDisplayed()
        compose.tagList().performScrollToNode(hasText("More"))
        compose.onNode(hasText("More") and hasClickAction()).performClick()

        assertEquals(listOf("moreReplies:a"), calls)
    }

    @Test
    fun `a failed first page offers retry and an empty one draws no section`() {
        show(PostCommentsState(top = CommentList().failed()))
        compose.onNodeWithText("Comments didn't load").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf("more"), calls)
    }

    @Test
    fun `an empty first page leaves the sheet without a comments section`() {
        show(loaded(), collapsed = false)
        compose.onNodeWithText("Comments").assertDoesNotExist()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsNotDisplayedOrMissing() {
    runCatching { assertIsDisplayed() }.onSuccess { throw AssertionError("expected the node to be scrolled out of view") }
}
