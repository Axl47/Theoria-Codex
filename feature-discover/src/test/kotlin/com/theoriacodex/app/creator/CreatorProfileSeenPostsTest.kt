package com.theoriacodex.app.creator

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.theoriacodex.app.creator.state.CreatorUiState
import com.theoriacodex.app.related.testRelatedShelf
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.testing.waitUntilSettled
import com.theoriacodex.app.ui.components.FeedMarks
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.util.concurrent.CopyOnWriteArraySet
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CreatorProfileSeenPostsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a followed creator's profile reports only the new posts it showed (ADR 0019)`() {
        val posts = (0 until 30).map { testPost(sourcePostId = "upload-$it") }
        val newest = posts.first().id
        val oldest = posts.last().id
        val seen = CopyOnWriteArraySet<PostId>()
        compose.setContent {
            MaterialTheme {
                CreatorProfileScreen(
                    state = CreatorUiState(
                        creator = CreatorProfile(SourceKey.PIXIV, "Artist", profileId = "artist"),
                        queryHash = "creator:PIXIV:artist",
                        results = posts,
                    ),
                    newPostIds = setOf(newest, oldest),
                    onNewPostsSeen = { seen += it },
                    related = testRelatedShelf(),
                    marks = FeedMarks.NONE,
                    onToggleLike = {},
                    onAction = {},
                    onOpenUrl = {},
                )
            }
        }
        compose.waitUntilSettled(5_000) { newest in seen }
        compose.runOnIdle { assertFalse(oldest in seen) }
    }
}
