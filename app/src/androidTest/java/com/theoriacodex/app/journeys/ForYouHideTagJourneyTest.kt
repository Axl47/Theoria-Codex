package com.theoriacodex.app.journeys

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.source.displayName
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import org.junit.Test

class ForYouHideTagJourneyTest : AppJourneyFixture() {
    @Test
    fun strikingATagHidesItForTheProfileAndUndoInThePanelRestoresIt() {
        io {
            container.data.recentsRepository.recordSearch(
                Query(QueryMode.Unified, listOf("pixiv_seed"), emptyList(), SortMode.NEWEST, null, null),
                "for_you:hide", RecentSearchKind.FYP, listOf(SourceKey.PIXIV),
                mapOf(SourceKey.PIXIV to listOf("pixiv_seed")),
            )
        }
        launch()
        library(LibrarySection.History)
        openRecentsSection("Searches")
        activate(compose.onNodeWithTag("Recent search:for_you:hide"))
        activate(compose.onNodeWithText("${SourceKey.PIXIV.displayName()}: pixiv_seed"))
        activate(compose.onNodeWithContentDescription("Hide pixiv_seed"))
        compose.waitUntil(10_000) { hidden() == listOf(ForYouBlacklistEntry(SourceKey.PIXIV, listOf("pixiv_seed"))) }
        compose.onNodeWithText("Hid pixiv_seed").assertExists()
        activate(compose.onNodeWithText("Undo"))
        compose.waitUntil(10_000) { hidden().isEmpty() }
    }

    private fun hidden() = io {
        val settings = container.data.settingsRepository.observeSettings().first()
        settings.forYouBlacklistByProfile[settings.activeProfileId].orEmpty()
    }
}
