package com.theoriacodex.app.journeys

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

class LiftToCollectJourneyTest : AppJourneyFixture() {
    @Test
    fun liftingACardOntoACodexTargetSavesThePostThere() {
        val first = posts.getValue(SourceKey.PIXIV).first()
        val collection = io {
            container.data.recentsRepository.recordSearch(
                Query(QueryMode.Unified, listOf("pixiv_seed"), emptyList(), SortMode.NEWEST, null, null),
                "for_you:lift", RecentSearchKind.FYP, listOf(SourceKey.PIXIV),
                mapOf(SourceKey.PIXIV to listOf("pixiv_seed")),
            )
            container.content.ensureCodex(profileScopedCodexId("profile-main", "lift"), "Lift target")
        }
        launch()
        tab("Recents")
        openRecentsSection("Searches")
        compose.onNodeWithTag("Recent search:for_you:lift").performClick()
        val cardTag = searchCardTestTag(first.id)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(cardTag).fetchSemanticsNodes().isNotEmpty() }
        val card = compose.onNodeWithTag(cardTag)
        card.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
        }
        val targetTag = "Collect target codex:${collection.codexId}"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(targetTag).fetchSemanticsNodes().isNotEmpty() }
        val target = compose.onNodeWithTag(targetTag).fetchSemanticsNode().boundsInRoot.center
        val cardTopLeft = card.fetchSemanticsNode().boundsInRoot.topLeft
        card.performTouchInput { moveTo(target - cardTopLeft) }
        compose.waitForIdle()
        card.performTouchInput { up() }

        compose.waitUntil(10_000) { io { container.content.observeCodexItems(collection.codexId).first().isNotEmpty() } }
        assertEquals(listOf(first.id), io { container.content.observeCodexItems(collection.codexId).first().map { it.postId } })
    }
}
