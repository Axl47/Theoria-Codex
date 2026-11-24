package com.theoriacodex.app.journeys

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostMenuJourneyTest : AppJourneyFixture() {
    @Test
    fun searchCreatorProfileAndSavedHoldMenusOfferTheSameRows() {
        val post = posts.getValue(SourceKey.PIXIV).first()
        val card = compose.onNodeWithTag(searchCardTestTag(post.id))
        io {
            container.data.recentsRepository.recordSearch(
                Query(QueryMode.Source(SourceKey.PIXIV), listOf("multi"), emptyList(), SortMode.POPULAR, null, null),
                RECENT_SEARCH,
            )
        }
        launch()
        library(LibrarySection.History)
        openRecentsSection("Searches")
        activate(compose.onNodeWithTag("Recent search:$RECENT_SEARCH"))
        waitFor("search card is displayed") { card.isDisplayed() }

        longPress(card)
        assertSharedRows("Like")
        activate(compose.onAllNodesWithText("Like").onFirst())
        waitFor("the post is liked") {
            io { container.data.likesRepository.observeLikedPostIds("profile-main").first() } == setOf(post.id)
        }
        longPress(card)
        assertSharedRows("Unlike")

        activate(compose.onNodeWithContentDescription(CREATOR_LINK))
        waitFor("creator page shows the post") { card.isDisplayed() }
        waitFor("the creator page itself shows no creator link") { !hasCreatorLink() }
        longPress(card)
        assertSharedRows("Unlike")
        back()
        val laterCard = searchCardTestTag(posts.getValue(SourceKey.PIXIV)[LATER_CARD].id)
        val creatorGrid = hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        val feedCard = SemanticsMatcher("is a feed card") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("search_card_") == true
        }
        compose.onNode(creatorGrid and hasAnyDescendant(feedCard), useUnmergedTree = true)
            .performScrollToNode(hasTestTag(laterCard))
        waitFor("the first card scrolled away") { !card.isDisplayed() }
        longPress(compose.onNodeWithTag(laterCard))
        activate(compose.onNodeWithContentDescription(CREATOR_LINK))
        compose.waitForIdle()
        assertTrue("the creator page keeps its scroll", compose.onNodeWithTag(laterCard).isDisplayed() && !card.isDisplayed())
        back()

        library(LibrarySection.Saved)
        waitFor("Saved shows the liked post") { card.isDisplayed() }
        longPress(card)
        assertSharedRows("Unlike")
        waitFor("Saved's menu offers Go to Search") { hasText("Go to Search") }
        back()
        assertEquals(setOf(post.id), io { container.data.likesRepository.observeLikedPostIds("profile-main").first() })
    }

    private fun assertSharedRows(like: String) {
        listOf(like, "Save to Codex", "Save to device", "Copy tags").forEach { label ->
            waitFor("the hold menu offers $label") { hasText(label) }
        }
        waitFor("the hold menu links the creator") { hasCreatorLink() }
    }

    private fun hasCreatorLink() = compose.onAllNodesWithContentDescription(CREATOR_LINK).fetchSemanticsNodes().isNotEmpty()

    private fun hasText(label: String) = compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()

    private fun back() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitForIdle()
    }

    private companion object {
        const val RECENT_SEARCH = "journey:post-menu"
        const val CREATOR_LINK = "Open Journey artist's profile"
        const val LATER_CARD = 16
    }
}
