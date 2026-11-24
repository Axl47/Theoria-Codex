package com.theoriacodex.app.journeys

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.theoriacodex.app.codex.likesCodexIdForProfile
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.recommend.FOR_YOU_VIEW_TOGGLE_TAG
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouPostActionsJourneyTest : AppJourneyFixture() {
    @Test
    fun gridLikeRelatedShelfSaveAndCreatorActionsUseTheRealOwners() {
        val first = posts.getValue(SourceKey.PIXIV).first()
        val collection = io { container.content.ensureCodex(profileScopedCodexId("profile-main", "actions"), "FYP saved") }
        launch()
        tab("For You")
        // The grid shows the Mix's posts; the first lane's first post is among its first cards.
        activate(compose.onNodeWithTag(FOR_YOU_VIEW_TOGGLE_TAG))
        val card = searchCardTestTag(first.id)
        waitFor("For You card for ${first.id} is displayed", timeoutMillis = 15_000) { compose.onAllNodesWithTag(card).fetchSemanticsNodes().isNotEmpty() }
        val rootsBeforeLike = container.registry.requests.count { it.pageToken == null }
        compose.onNode(hasContentDescription("Like post") and hasAnyAncestor(hasTestTag(card)), useUnmergedTree = true)
            .performClick()
        waitFor("More like this shelf appears after Like") { compose.onAllNodesWithText("More like this").fetchSemanticsNodes().isNotEmpty() }
        // Whichever lane answered first leads the grid, so the liked card's shelf may open below the fold.
        val grid = hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            hasAnyDescendant(hasTestTag(card))
        compose.onNode(grid, useUnmergedTree = true).performScrollToNode(hasText("More like this"))
        compose.onNodeWithText("More like this").assertIsDisplayed()
        assertEquals(setOf(first.id), io { container.data.likesRepository.observeLikedPostIds("profile-main").first() })
        assertEquals(listOf(first.id), io { container.content.observeCodexItems(likesCodexIdForProfile("profile-main")).first().map { it.postId } })
        assertEquals(rootsBeforeLike, container.registry.requests.count { it.pageToken == null })
        // For You leaves nothing in the search history.
        assertTrue(io { container.data.recentsRepository.observeSearches().first() }.isEmpty())
        compose.onNodeWithText("Dismiss").performClick()
        longPress(compose.onNodeWithTag(card))
        activate(compose.onNodeWithText("Save to Codex"))
        waitFor("FYP saved collection is offered as a save destination") { compose.onAllNodesWithText("FYP saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("FYP saved").performClick()
        waitFor("collection holds a saved post") { io { container.content.observeCodexItems(collection.codexId).first().isNotEmpty() } }
        assertEquals(listOf(first.id), io { container.content.observeCodexItems(collection.codexId).first().map { it.postId } })
        waitFor("For You save count is 1") { io { container.data.statisticsRepository.observeStatistics().first().forYouSaveCount == 1L } }
        longPress(compose.onNodeWithTag(card))
        activate(compose.onNodeWithContentDescription("Open Journey artist's profile"))
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithTag(card).assertIsDisplayed()
        pressSystemBack()
        compose.onNodeWithTag(card).assertIsDisplayed()
        assertEquals(1L, io { container.data.statisticsRepository.observeStatistics().first().forYouSaveCount })
    }
}
