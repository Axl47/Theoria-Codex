package com.theoriacodex.app.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.Post
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchCardCommentMarkTest {
    @get:Rule val compose = createComposeRule()

    private fun show(hint: CommentHint?) {
        val post: Post = testPost().copy(commentHint = hint)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(200.dp)) {
                    SearchCardBadges(
                        post, viewedMediaNumber = null, watchedDescription = null, liked = false, onToggleLike = null,
                        metadataLabel = null, showSourceBadge = false, showPreviewContext = false, durationLabel = null,
                    )
                }
            }
        }
    }

    @Test
    fun `a listing count reads as that many comments`() {
        show(CommentHint(12))
        compose.onNode(hasContentDescription("12 comments")).assertExists()
    }

    @Test
    fun `a listing that only says there are some gets the icon alone`() {
        show(CommentHint.Some)
        compose.onNode(hasContentDescription("Has comments")).assertExists()
    }

    @Test
    fun `no comments or no hint adds no mark`() {
        show(CommentHint.None)
        compose.onNode(hasContentDescription("Has comments")).assertDoesNotExist()
        compose.onNode(hasContentDescription("0 comments")).assertDoesNotExist()
    }
}
