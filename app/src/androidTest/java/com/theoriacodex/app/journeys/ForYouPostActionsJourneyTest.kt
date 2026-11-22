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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
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
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(card).fetchSemanticsNodes().isNotEmpty() }
        val rootsBeforeLike = container.registry.requests.count { it.pageToken == null }
        compose.onNode(hasContentDescription("Like post") and hasAnyAncestor(hasTestTag(card)), useUnmergedTree = true)
            .performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("More like this").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("More like this").assertIsDisplayed()
        assertEquals(setOf(first.id), io { container.data.likesRepository.observeLikedPostIds("profile-main").first() })
        assertEquals(listOf(first.id), io { container.content.observeCodexItems(likesCodexIdForProfile("profile-main")).first().map { it.postId } })
        assertEquals(rootsBeforeLike, container.registry.requests.count { it.pageToken == null })
        // For You leaves nothing in the search history.
        assertTrue(io { container.data.recentsRepository.observeSearches().first() }.isEmpty())
        compose.onNodeWithText("Dismiss").performClick()
        longPress(compose.onNodeWithTag(card))
        activate(compose.onNodeWithText("Save to Codex"))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("FYP saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("FYP saved").performClick()
        compose.waitUntil(10_000) { io { container.content.observeCodexItems(collection.codexId).first().isNotEmpty() } }
        assertEquals(listOf(first.id), io { container.content.observeCodexItems(collection.codexId).first().map { it.postId } })
        compose.waitUntil(10_000) { io { container.data.statisticsRepository.observeStatistics().first().forYouSaveCount == 1L } }
        longPress(compose.onNodeWithTag(card))
        activate(compose.onNodeWithContentDescription("Open Journey artist's profile"))
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithTag(card).assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.onNodeWithTag(card).assertIsDisplayed()
        assertEquals(1L, io { container.data.statisticsRepository.observeStatistics().first().forYouSaveCount })
    }
}
