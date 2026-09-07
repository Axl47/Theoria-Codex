package com.theoriacodex.app.journeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.theoriacodex.app.codex.likesCodexIdForProfile
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test

class ForYouPostActionsJourneyTest : AppJourneyFixture() {
    @Test
    fun replayLikeRelatedShelfSaveAndCreatorActionsUseTheRealOwners() {
        val first = posts.getValue(SourceKey.PIXIV).first()
        val collection = io {
            container.data.recentsRepository.recordSearch(
                Query(QueryMode.Unified, listOf("pixiv_seed"), emptyList(), SortMode.NEWEST, null, null),
                "for_you:actions", RecentSearchKind.FYP, listOf(SourceKey.PIXIV),
                mapOf(SourceKey.PIXIV to listOf("pixiv_seed")),
            )
            container.content.ensureCodex(profileScopedCodexId("profile-main", "actions"), "FYP saved")
        }
        launch()
        tab("Recents")
        compose.onNodeWithText("FYP").performClick()
        compose.onNodeWithTag("Recent search:for_you:actions").performClick()
        val card = searchCardTestTag(first.id)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(card).fetchSemanticsNodes().isNotEmpty() }
        val rootsBeforeLike = container.registry.requests.count { it.pageToken == null }
        val historyBeforeLike = io { container.data.recentsRepository.observeSearches().first() }
        compose.onNode(hasContentDescription("Like post") and hasAnyAncestor(hasTestTag(card)), useUnmergedTree = true)
            .performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("More like this").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("More like this").assertIsDisplayed()
        assertEquals(setOf(first.id), io { container.data.likesRepository.observeLikedPostIds("profile-main").first() })
        assertEquals(listOf(first.id), io { container.content.observeCodexItems(likesCodexIdForProfile("profile-main")).first().map { it.postId } })
        assertEquals(rootsBeforeLike, container.registry.requests.count { it.pageToken == null })
        assertEquals(historyBeforeLike, io { container.data.recentsRepository.observeSearches().first() })
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithTag(card).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Save to Codex").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("FYP saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("FYP saved").performClick()
        compose.waitUntil(10_000) { io { container.content.observeCodexItems(collection.codexId).first().isNotEmpty() } }
        assertEquals(listOf(first.id), io { container.content.observeCodexItems(collection.codexId).first().map { it.postId } })
        compose.waitUntil(10_000) { io { container.data.statisticsRepository.observeStatistics().first().forYouSaveCount == 1L } }
        compose.onNodeWithTag(card).performTouchInput { longClick() }
        compose.onNodeWithText("Journey artist").performClick()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithTag(card).assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.onNodeWithTag(card).assertIsDisplayed()
        assertEquals(1L, io { container.data.statisticsRepository.observeStatistics().first().forYouSaveCount })
    }
}
