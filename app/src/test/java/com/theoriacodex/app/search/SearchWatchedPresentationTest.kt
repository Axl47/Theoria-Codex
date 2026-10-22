package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.state.SearchContentUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.ReadingPosition
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.SourceKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SearchWatchedPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `visible watched search result shares Recents progress and latest watched time`() {
        val post = testPost(SourceKey.PIXIV, "watched").copy(mediaCount = 12)
        val now = System.currentTimeMillis()
        val presentation = postWatchPresentation(
            listOf(ReadingPosition(post.id, RecentPostSection.WATCHED, 2, now)),
            listOf(RecentPostEntry(post, now - 120_000, ViewerStreamSource.SEARCH, null, maxViewedMediaNumber = 9)),
        )
        compose.setContent {
            CompositionLocalProvider(LocalPostWatchPresentation provides presentation) {
                MaterialTheme {
                    Box(Modifier.size(400.dp, 700.dp)) {
                        SearchScreen(
                            state = SearchUiState(content = SearchContentUiState(results = listOf(post), hasExecutedSearch = true)),
                            creatorBrowsingSources = emptySet(), onAction = {},
                            watchedPostIds = setOf(post.id),
                            resolvePostById = { null }, recoverPostMedia = { _, _ -> null },
                            tagVideoCountProvider = { _, _ -> null }, fetchTagVideoCounts = { _, _ -> emptyMap() },
                            onOpenCreatorProfile = {}, onOpenLegacyCreatorProfile = {},
                            onRequestSaveToCodex = {}, onSaveToDevice = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("9/12").assertIsDisplayed()
        compose.onNodeWithText("Just now").assertIsDisplayed()
    }
}
