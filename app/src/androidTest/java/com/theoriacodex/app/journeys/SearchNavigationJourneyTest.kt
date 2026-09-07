package com.theoriacodex.app.journeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.QueryHash
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchNavigationJourneyTest : AppJourneyFixture() {
    @Test
    fun groupedSearchPagesReturnsFromViewerAndSurvivesActivityRecreation() {
        launch()
        compose.onNodeWithTag("Search query input").performClick()
        compose.onNodeWithContentDescription("Pixiv").performClick()
        addSearchTag("landscape")
        addSearchTag("blue")
        compose.onNodeWithText("blue").performClick()
        compose.onNodeWithText("Tag").performTextInput("green")
        compose.onNodeWithText("Add alternative").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(2) {
            if (compose.onAllNodesWithTag("Include group sheet").fetchSemanticsNodes().isNotEmpty()) {
                device.pressBack()
                compose.waitForIdle()
            }
        }
        compose.onNodeWithText("Apply").performClick()
        waitForCard(0)
        compose.onNodeWithText("Pixiv · landscape AND (blue OR green)").assertIsDisplayed()
        val root = container.registry.requests.single { it.source == SourceKey.PIXIV && it.pageToken == null }
        assertEquals(listOf(listOf("landscape"), listOf("blue", "green")),
            root.query.effectiveIncludeTermGroups.map { group -> group.terms.map { it.value } })
        compose.onNodeWithTag("Search results", useUnmergedTree = true).performScrollToIndex(18)
        compose.waitUntil(10_000) { container.registry.requests.any { it.pageToken == "20" } }
        compose.onNodeWithTag("Search results", useUnmergedTree = true).performScrollToIndex(22)
        val cardTag = searchCardTestTag(posts.getValue(SourceKey.PIXIV)[22].id)
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        val before = compose.onNodeWithTag(cardTag).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag(cardTag).performClick()
        compose.waitUntil(10_000) {
            io { container.data.statisticsRepository.observeStatistics().first().watchedPostCount == 1L }
        }
        scenario.recreate()
        compose.onNodeWithContentDescription(requireNotNull(posts.getValue(SourceKey.PIXIV)[22].title)).assertIsDisplayed()
        device.pressBack()
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag(cardTag).fetchSemanticsNode().boundsInRoot.top, 2f)
        tab("Settings")
        tab("Search")
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(cardTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag(cardTag).fetchSemanticsNode().boundsInRoot.top, 2f)
        assertEquals(1, io { container.data.recentsRepository.observeSearches().first().size })
        assertEquals(1, io { container.data.recentsRepository.observeWatchedPosts().first().size })
        val statistics = io { container.data.statisticsRepository.observeStatistics().first() }
        assertEquals(1L, statistics.searchCount)
        assertEquals(1L, statistics.watchedPostCount)
    }

    @Test
    fun coldGraphRelaunchRestoresAppliedSourceQueryAndCanonicalScrollFromDisk() {
        launch()
        compose.onNodeWithTag("Search query input").performClick()
        compose.onNodeWithContentDescription("Pixiv").performClick()
        addSearchTag("landscape")
        compose.onNodeWithText("Apply").performClick()
        waitForCard(0)
        val applied = container.registry.requests.single { it.source == SourceKey.PIXIV && it.pageToken == null }.query
        val queryHash = QueryHash.from(applied)
        compose.onNodeWithTag("Search results", useUnmergedTree = true).performScrollToIndex(6)
        val cardTag = searchCardTestTag(posts.getValue(SourceKey.PIXIV)[6].id)
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        val top = compose.onNodeWithTag(cardTag).fetchSemanticsNode().boundsInRoot.top
        compose.waitUntil(10_000) {
            io { container.data.uiRestoreRepository.getSearchScrollState(queryHash)?.firstVisibleItemIndex == 6 }
        }
        val savedScroll = io { container.data.uiRestoreRepository.getSearchScrollState(queryHash) }
        val previousDatabase = container.database
        val previousSettings = container.settings

        coldGraphRelaunch()

        org.junit.Assert.assertNotSame(previousDatabase, container.database)
        org.junit.Assert.assertNotSame(previousSettings, container.settings)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(cardTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pixiv · landscape").assertIsDisplayed()
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        assertEquals(top, compose.onNodeWithTag(cardTag).fetchSemanticsNode().boundsInRoot.top, 2f)
        assertEquals(savedScroll, io { container.data.uiRestoreRepository.getSearchScrollState(queryHash) })
        assertEquals(applied, io { container.data.queryRepository.observeAppliedQuery("source:PIXIV").first() })
        val restoredRequest = container.registry.requests.single { it.pageToken == null }
        assertEquals(SourceKey.PIXIV, restoredRequest.source)
        assertEquals(applied, restoredRequest.query)
    }

    @Test
    fun recentsDispatchesExactMultiSearchAndColdFypReplayThroughNavigation() {
        val multi = query(listOf("multi"), SortMode.POPULAR)
        val sourceTags = linkedMapOf(SourceKey.GELBOORU to listOf("gelbooru_seed"), SourceKey.PIXIV to listOf("pixiv_seed"))
        io {
            container.data.recentsRepository.recordSearch(multi, "journey:multi", RecentSearchKind.MULTI_SEARCH,
                listOf(SourceKey.GELBOORU, SourceKey.PIXIV))
            container.data.recentsRepository.recordSearch(query(listOf("legacy_wrong_tag"), SortMode.TOP),
                "for_you:journey", RecentSearchKind.FYP, sourceTags.keys.toList(), sourceTags)
        }
        launch()
        tab("Recents")
        compose.onNodeWithText("Searches").performClick()
        compose.onNodeWithTag("Recent search:journey:multi").performClick()
        waitForCard(0)
        val multiRequests = container.registry.requests.filter { it.pageToken == null }
        assertEquals(setOf(SourceKey.GELBOORU, SourceKey.PIXIV), multiRequests.map { it.source }.toSet())
        assertTrue(multiRequests.all { it.query.includeTags == listOf("multi") && it.query.sort == SortMode.POPULAR })
        assertTrue(io { container.data.queryRepository.observeAppliedQuery("unified").first() == null })
        container.registry.requests.clear()
        tab("Recents")
        compose.onNodeWithText("FYP").performClick()
        compose.onNodeWithTag("Recent search:for_you:journey").performClick()
        compose.waitUntil(10_000) { container.registry.requests.count { it.pageToken == null } >= 2 }
        val replay = container.registry.requests.filter { it.pageToken == null }
        assertEquals(sourceTags, replay.associate { it.source to it.query.includeTags })
        assertTrue(replay.all { it.query.sort == SortMode.TOP })
        assertTrue(io { container.data.likesRepository.observeLikes("profile-main").first().isEmpty() })
        compose.onNodeWithTag(searchCardTestTag(posts.getValue(SourceKey.PIXIV)[0].id)).assertIsDisplayed()
    }

    private fun addSearchTag(value: String) {
        compose.onNodeWithTag("Search query input").performTextInput(value)
        compose.onNodeWithTag("Search query input").performImeAction()
    }

    private fun waitForCard(index: Int) {
        val tag = searchCardTestTag(posts.getValue(SourceKey.PIXIV)[index].id)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun query(tags: List<String>, sort: SortMode) = Query(
        QueryMode.Unified, tags, emptyList(), sort, null, null,
    )
}
