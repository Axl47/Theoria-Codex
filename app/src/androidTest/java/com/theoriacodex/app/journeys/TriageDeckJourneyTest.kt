package com.theoriacodex.app.journeys

import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import com.theoriacodex.app.codex.profileScopedCodexId
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertNotEquals
import org.junit.Ignore
import org.junit.Test

class TriageDeckJourneyTest : AppJourneyFixture() {
    /**
     * ORCHESTRATOR HOOK. The deck has no menu entry yet; the menus call `LocalTriageOpener.current.openTriage`
     * with the feed's source and key. Once an entry exists, make this the real path (Search results, feed menu,
     * triage) and remove the @Ignore on the test.
     */
    private fun openTriageDeck() {
        activate(compose.onNodeWithContentDescription("Search"))
        error("Open the triage deck over Search results through the feed menu entry the orchestrator wires")
    }

    @Ignore("Needs the feed menu entry that opens the deck; see openTriageDeck")
    @Test
    fun swipingRightLikesAndSwipingUpSavesToTheSuggestedCodex() {
        val codexId = io {
            container.content.ensureCodex(profileScopedCodexId("profile-main", "triage"), "Triage skies").codexId
        }
        launch()
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
}
