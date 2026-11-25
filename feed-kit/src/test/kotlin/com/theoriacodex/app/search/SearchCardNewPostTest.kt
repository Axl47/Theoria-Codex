package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.components.FeedDurations
import com.theoriacodex.app.ui.components.FeedPostCard
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchCardNewPostTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a new post's card tells TalkBack it is new and an ordinary card does not (ADR 0152)`() {
        val fresh = testPost(sourcePostId = "fresh")
        val ordinary = testPost(sourcePostId = "ordinary")
        compose.setContent {
            MaterialTheme {
                val cards = rememberFeedCardEnvironment(null, emptySet(), emptyMap(), FeedDurations.NONE, null, newPostIds = setOf(fresh.id))
                Column {
                    listOf(fresh, ordinary).forEach { post ->
                        FeedPostCard(post, cards, onClick = {}, onLongPress = null, modifier = Modifier.size(160.dp))
                    }
                }
            }
        }

        compose.onNodeWithTag(searchCardTestTag(fresh.id)).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "New"))
        compose.onNodeWithTag(searchCardTestTag(ordinary.id)).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }
}
