package com.theoriacodex.app.journeys

import com.theoriacodex.app.library.LibrarySection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.QueryHash
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchNavigationJourneyTest : AppJourneyFixture() {
    @Test
    fun restoredTagsCanBeResetWithoutEditingThem() {
        launch()
        activate(compose.onNodeWithTag("Search bar"))
        activate(compose.onNode(hasContentDescription("Search source:", substring = true) and
            hasAnyAncestor(hasTestTag("Search composer"))))
        activate(compose.onNodeWithContentDescription("Pixiv"))
        addSearchTag("landscape")
        submitSearch()
        waitFor("applied query includes the landscape tag") {
            io { container.data.queryRepository.observeAppliedQuery("source:PIXIV").first() }
                ?.includeTags?.contains("landscape") == true
        }
        coldGraphRelaunch()
        activate(compose.onNodeWithTag("Search bar"))
        val restoredTag = compose.onNode(hasText("landscape") and hasAnyAncestor(hasTestTag("Search composer")))
        restoredTag.assertIsDisplayed()
        activate(compose.onNodeWithContentDescription("More search actions"))
        compose.onNodeWithText("Reset").assertIsEnabled().performClick()
        restoredTag.assertDoesNotExist()
        activate(compose.onNodeWithContentDescription("More search actions"))
        compose.onNodeWithText("Reset").assertIsNotEnabled()
    }

    @Test
    fun favoriteTagFromSettingsAppearsInSavedAndCyclesThroughTheSearch() {
        io {
            val profile = container.settings.observeSettings().first().activeProfileId
            container.settings.addFavoriteTag(profile, SourceKey.PIXIV, listOf("journeytag"))
        }
        launch()
        activate(compose.onNodeWithTag("Search bar"))
        compose.onNodeWithText("Saved tags").assertIsDisplayed()
        activate(compose.onNode(hasText("journeytag") and hasClickLabel("Include journeytag in Search")))
        activate(compose.onNode(hasText("journeytag") and hasClickLabel("Exclude journeytag from Search")))
        compose.onNodeWithText("Excluded").assertIsDisplayed()
        submitSearch()
        waitFor("Provider excludes the saved tag") {
            container.registry.requests.any { request ->
                request.source == SourceKey.PIXIV && request.pageToken == null &&
                    request.query.excludeTags.contains("journeytag")
            }
        }
    }

    private fun hasClickLabel(label: String) = SemanticsMatcher("click label is $label") { node ->
        SemanticsActions.OnClick in node.config && node.config[SemanticsActions.OnClick].label == label
    }

    @Test
    fun groupedSearchPagesReturnsFromViewerAndSurvivesActivityRecreation() {
        launch()
        activate(compose.onNodeWithTag("Search bar"))
        activate(compose.onNode(hasContentDescription("Search source:", substring = true) and
            hasAnyAncestor(hasTestTag("Search composer"))))
        activate(compose.onNodeWithContentDescription("Pixiv"))
        addSearchTag("landscape")
        addSearchTag("blue")
        activate(compose.onNode(hasText("blue") and hasClickAction() and
            hasAnyAncestor(hasTestTag("Search composer"))))
        activate(compose.onNodeWithText("Add an alternative"))
        addSearchTag("green")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Touch mode is reported if the composer fails to collapse after the keyboard submits.
        var nativeTouchModeBeforeApply = false
        scenario.onActivity { nativeTouchModeBeforeApply = it.window.decorView.isInTouchMode }
        submitSearch()
        // Search runs live, so each tag edit may reach the provider before the final grouped query does; that one is asked once.
        val expectedGroups = listOf(listOf("landscape"), listOf("blue", "green"))
        fun groupedRoots() = container.registry.requests.toList().filter { request ->
            request.source == SourceKey.PIXIV && request.pageToken == null &&
                request.query.effectiveIncludeTermGroups.map { group -> group.terms.map { it.value } } == expectedGroups
        }
        waitFor("Provider receives the grouped search") { groupedRoots().isNotEmpty() }
        val summary = compose.onNodeWithText("landscape, blue or green", useUnmergedTree = true)
        try {
            waitFor("Grouped query is applied and its field is collapsed") {
                summary.isDisplayed()
            }
        } catch (timeout: ComposeTimeoutException) {
            var nativeTouchModeAfterApply = false
            scenario.onActivity { nativeTouchModeAfterApply = it.window.decorView.isInTouchMode }
            throw AssertionError(
                "Native touch mode: $nativeTouchModeBeforeApply -> $nativeTouchModeAfterApply\n" +
                    "Provider queries: ${container.registry.requests.map { it.query }}\n" +
                    compose.onRoot(useUnmergedTree = true).printToString(),
                timeout,
            )
        }
        waitForCard(0)
        summary.assertIsDisplayed()
        assertEquals(1, groupedRoots().size)
        scrollToFirstVisibleCard(SourceKey.PIXIV, 18)
        waitFor("provider received the next page request") { container.registry.requests.any { it.pageToken == "20" } }
        scrollToFirstVisibleCard(SourceKey.PIXIV, 22)
        val cardTag = searchCardTestTag(posts.getValue(SourceKey.PIXIV)[22].id)
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        val before = cardOffsetInViewport(cardTag)
        activate(compose.onNodeWithTag(cardTag))
        waitFor("1 post watched is recorded") {
            io { container.data.statisticsRepository.observeStatistics().first().watchedPostCount == 1L }
        }
        scenario.recreate()
        compose.onNodeWithContentDescription(requireNotNull(posts.getValue(SourceKey.PIXIV)[22].title)).assertIsDisplayed()
        device.pressBack()
        waitForCard(22)
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        assertEquals(before, cardOffsetInViewport(cardTag), 2f)
        tab("Library")
        tab("Search")
        waitForCard(22)
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        scenario.recreate()
        waitFor("search card is displayed after recreating the activity") { compose.onNodeWithTag(cardTag).isDisplayed() }
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        assertEquals(before, cardOffsetInViewport(cardTag), 2f)
        assertEquals(1, io { container.data.recentsRepository.observeSearches().first().size })
        assertEquals(1, io { container.data.recentsRepository.observeWatchedPosts().first().size })
        val statistics = io { container.data.statisticsRepository.observeStatistics().first() }
        assertEquals(1L, statistics.searchCount)
        assertEquals(1L, statistics.watchedPostCount)
    }

    @Test
    fun coldGraphRelaunchRestoresAppliedSourceQueryAndCanonicalScrollFromDisk() {
        launch()
        activate(compose.onNodeWithTag("Search bar"))
        activate(compose.onNode(hasContentDescription("Search source:", substring = true) and
            hasAnyAncestor(hasTestTag("Search composer"))))
        activate(compose.onNodeWithContentDescription("Pixiv"))
        addSearchTag("landscape")
        submitSearch()
        waitForCard(0)
        val applied = container.registry.requests.single { it.source == SourceKey.PIXIV && it.pageToken == null }.query
        val queryHash = QueryHash.from(applied)
        scrollToFirstVisibleCard(SourceKey.PIXIV, 6)
        val cardTag = searchCardTestTag(posts.getValue(SourceKey.PIXIV)[6].id)
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        val offset = cardOffsetInViewport(cardTag)
        val expectedScroll = SearchScrollState(6, -offset.roundToInt())
        assertTrue("A real drag should retain its nonzero pixel offset; card top is $offset px from the viewport's",
            expectedScroll.firstVisibleItemOffsetPx > 0)
        waitFor("saved scroll position matches the expected position") {
            io { container.data.uiRestoreRepository.getSearchScrollState(queryHash) == expectedScroll }
        }
        val savedScroll = requireNotNull(io { container.data.uiRestoreRepository.getSearchScrollState(queryHash) })
        val previousDatabase = container.database
        val previousSettings = container.settings

        coldGraphRelaunch()

        org.junit.Assert.assertNotSame(previousDatabase, container.database)
        org.junit.Assert.assertNotSame(previousSettings, container.settings)
        waitFor("search card is displayed after relaunching the graph") { compose.onNodeWithTag(cardTag).isDisplayed() }
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        assertEquals(offset, cardOffsetInViewport(cardTag), 2f)
        assertEquals(savedScroll, io { container.data.uiRestoreRepository.getSearchScrollState(queryHash) })
        assertEquals(applied, io { container.data.queryRepository.observeAppliedQuery("source:PIXIV").first() })
        val restoredRequest = container.registry.requests.single { it.pageToken == null }
        assertEquals(SourceKey.PIXIV, restoredRequest.source)
        assertEquals(applied, restoredRequest.query)
        compose.onNodeWithText("landscape", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun recentsDispatchesExactMultiSearchThroughNavigation() {
        val multi = query(listOf("multi"), SortMode.POPULAR)
        io {
            container.data.recentsRepository.recordSearch(multi, "journey:multi", RecentSearchKind.MULTI_SEARCH,
                listOf(SourceKey.GELBOORU, SourceKey.PIXIV))
        }
        launch()
        library(LibrarySection.History)
        openRecentsSection("Searches")
        activate(compose.onNodeWithTag("Recent search:journey:multi"))
        waitForCard(0)
        val multiRequests = container.registry.requests.filter { it.pageToken == null }
        assertEquals(setOf(SourceKey.GELBOORU, SourceKey.PIXIV), multiRequests.map { it.source }.toSet())
        assertTrue(multiRequests.all { it.query.includeTags == listOf("multi") && it.query.sort == SortMode.POPULAR })
        assertTrue(io { container.data.queryRepository.observeAppliedQuery("unified").first() == null })
    }

    private fun cardOffsetInViewport(tag: String): Float {
        val cardTop = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
        val viewportTop = compose.onNodeWithTag("Search results", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.y
        return cardTop - viewportTop
    }

    private fun waitForCard(index: Int) {
        val tag = searchCardTestTag(posts.getValue(SourceKey.PIXIV)[index].id)
        waitFor("Pixiv search card $index is displayed") { compose.onNodeWithTag(tag).isDisplayed() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun query(tags: List<String>, sort: SortMode) = Query(
        QueryMode.Unified, tags, emptyList(), sort, null, null,
    )
}
