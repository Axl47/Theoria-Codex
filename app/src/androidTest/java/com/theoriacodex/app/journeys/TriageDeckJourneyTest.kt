package com.theoriacodex.app.journeys

import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TriageDeckJourneyTest : AppJourneyFixture() {
    /** Runs a Search from Recents over the fixture posts, then opens the deck from the filter sheet's Triage action. */
    private fun openTriageDeck() {
        io {
            container.data.recentsRepository.recordSearch(
                Query(QueryMode.Source(SourceKey.PIXIV), listOf("multi"), emptyList(), SortMode.POPULAR, null, null),
                "triage",
            )
        }
        launch()
        library(LibrarySection.History)
        openRecentsSection("Searches")
        activate(compose.onNodeWithTag("Recent search:triage"))
        val card = compose.onNodeWithTag(searchCardTestTag(posts.getValue(SourceKey.PIXIV).first().id))
        compose.waitUntil(10_000) { card.isDisplayed() }
        activate(compose.onNodeWithContentDescription("Filter and sort"))
        activate(compose.onNodeWithText("Triage"))
    }

    @Test
    fun swipingRightLikesAndSwipingUpSavesToTheSuggestedCodex() {
        val codexId = io {
            container.content.ensureCodex(profileScopedCodexId("profile-main", "triage"), "Triage skies").codexId
        }
        openTriageDeck()
        val deck = compose.onNodeWithTag("triage-deck")
        compose.waitUntil(10_000) { deck.isDisplayed() }

        deck.performTouchInput { swipeRight() }
        compose.waitUntil(10_000) { io { container.data.likesRepository.observeLikedPostIds("profile-main").first().size == 1 } }
        compose.waitUntil(10_000) { compose.onNodeWithTag("triage-progress").isDisplayed() }

        deck.performTouchInput { swipeUp() }
        compose.waitUntil(10_000) { io { container.content.observeCodexItems(codexId).first().size == 1 } }

        val liked = io { container.data.likesRepository.observeLikedPostIds("profile-main").first() }.single()
        val saved = io { container.content.observeCodexItems(codexId).first() }.single().postId
        assertNotEquals(liked, saved)
    }

    @Test
    fun onACodexSwipingLeftRemovesThePostAndUndoPutsItBack() {
        val codex = io {
            container.content.ensureCodex(profileScopedCodexId("profile-main", "triage-remove"), "Removal deck")
                .also { container.content.addItems(it.codexId, posts.getValue(SourceKey.PIXIV).take(3)) }
        }
        val original = io { container.content.observeCodexItems(codex.codexId).first().sortedBy { it.savedAtEpochMs } }
        launch()
        library(LibrarySection.Codices)
        activate(compose.onNodeWithText("Removal deck"))
        activate(compose.onNodeWithText("Triage"))
        val deck = compose.onNodeWithTag("triage-deck")
        compose.waitUntil(10_000) { deck.isDisplayed() }

        deck.performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { io { container.content.observeCodexItems(codex.codexId).first().size == 2 } }

        activate(compose.onNodeWithTag("triage-undo"))
        compose.waitUntil(10_000) { io { container.content.observeCodexItems(codex.codexId).first().size == 3 } }
        assertEquals(original, io { container.content.observeCodexItems(codex.codexId).first().sortedBy { it.savedAtEpochMs } })
    }
}
