package com.theoriacodex.app.journeys

import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.data.repository.CreatorFollowsRepository
import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test

class CreatorFollowJourneyTest : AppJourneyFixture() {
    @Test
    fun unfollowingOffersUndoInTheButtonAndUndoRestoresTheSameFollow() {
        openCreatorPage()
        activate(compose.onNodeWithText("Follow"))
        waitFor("one creator is followed") { follows().size == 1 }
        val followed = follows().single()

        activate(compose.onNodeWithText("Following"))
        waitFor("no creators are followed") { follows().isEmpty() }
        activate(compose.onNodeWithContentDescription("Unfollowed", substring = true))
        waitFor("creator is followed again") { follows().isNotEmpty() }

        assertEquals(listOf(followed), follows())
        waitFor("Following button is shown") { compose.onAllNodesWithText("Following").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun follows(): List<FollowedCreator> = io { CreatorFollowsRepository(container.data.settingsRepository).observe().first() }

    private fun openCreatorPage() {
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
        val card = compose.onNodeWithTag(searchCardTestTag(posts.getValue(SourceKey.PIXIV).first().id))
        waitFor("creator search card is displayed") { card.isDisplayed() }
        longPress(card)
        activate(compose.onNodeWithContentDescription("Open Journey artist's profile"))
    }

    private companion object {
        const val RECENT_SEARCH = "journey:creator-follow"
    }
}
