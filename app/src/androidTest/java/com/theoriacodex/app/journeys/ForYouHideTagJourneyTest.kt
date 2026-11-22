package com.theoriacodex.app.journeys

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import com.theoriacodex.app.recommend.FOR_YOU_UNDO_TAG
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test

class ForYouHideTagJourneyTest : AppJourneyFixture() {
    @Test
    fun hidingATagFromTheDeckHidesItForTheProfileAndUndoRestoresIt() {
        launch()
        tab("For You")
        // The deck gives TalkBack what a pull down onto a tag does.
        val offersHide = SemanticsMatcher("offers to hide a tag") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label.startsWith("Hide ") }
        }
        compose.waitUntil(15_000) { compose.onAllNodes(offersHide).fetchSemanticsNodes().isNotEmpty() }
        val hide = compose.onNode(offersHide).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label.startsWith("Hide ") }
        compose.runOnUiThread { hide.action() }
        compose.waitUntil(10_000) { hidden().size == 1 }
        assertEquals(1, hidden().single().tags.size)

        activate(compose.onNodeWithTag(FOR_YOU_UNDO_TAG))
        compose.waitUntil(10_000) { hidden().isEmpty() }
    }

    private fun hidden() = io {
        val settings = container.data.settingsRepository.observeSettings().first()
        settings.forYouBlacklistByProfile[settings.activeProfileId].orEmpty()
    }
}
