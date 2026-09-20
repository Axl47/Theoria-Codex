package com.theoriacodex.app.journeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
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
    fun groupedSearchPagesReturnsFromViewerAndSurvivesActivityRecreation() {
        launch()
        activate(compose.onNodeWithTag("Search query input"))
        activate(compose.onNodeWithContentDescription("Pixiv"))
        addSearchTag("landscape")
        addSearchTag("blue")
        activate(compose.onNodeWithText("blue"))
        val alternativeInput = compose.onNodeWithText("Tag")
        alternativeInput.performTextInput("green")
        compose.waitUntil(conditionDescription = "OR alternative input is ready", timeoutMillis = 10_000) {
            alternativeInput.fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "green"
        }
        activate(compose.onNodeWithText("Add alternative"))
        compose.waitUntil(conditionDescription = "Green alternative is committed to the group", timeoutMillis = 10_000) {
            alternativeInput.fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty() &&
                compose.onNodeWithText("green", substring = false).isDisplayed()
        }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitUntil(conditionDescription = "Include group sheet is fully dismissed", timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("Include group sheet").fetchSemanticsNodes().isEmpty()
        }
        // This assertion concerns touch-driven focus dismissal, so Apply receives a real tap.
        var nativeTouchModeBeforeApply = false
        scenario.onActivity { nativeTouchModeBeforeApply = it.window.decorView.isInTouchMode }
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(conditionDescription = "Provider receives the grouped search", timeoutMillis = 10_000) {
            container.registry.requests.any { it.source == SourceKey.PIXIV && it.pageToken == null }
        }
        val root = container.registry.requests.single { it.source == SourceKey.PIXIV && it.pageToken == null }
        assertEquals(listOf(listOf("landscape"), listOf("blue", "green")),
            root.query.effectiveIncludeTermGroups.map { group -> group.terms.map { it.value } })
        val summary = compose.onNodeWithText("Pixiv · landscape AND (blue OR green)")
        try {
            compose.waitUntil(conditionDescription = "Grouped query is applied and its field is collapsed", timeoutMillis = 10_000) {
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
        scrollToFirstVisibleCard(18)
        compose.waitUntil(10_000) { container.registry.requests.any { it.pageToken == "20" } }
        scrollToFirstVisibleCard(22)
        val cardTag = searchCardTestTag(posts.getValue(SourceKey.PIXIV)[22].id)
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        val before = cardOffsetInViewport(cardTag)
        activate(compose.onNodeWithTag(cardTag))
        compose.waitUntil(10_000) {
            io { container.data.statisticsRepository.observeStatistics().first().watchedPostCount == 1L }
        }
        scenario.recreate()
        compose.onNodeWithContentDescription(requireNotNull(posts.getValue(SourceKey.PIXIV)[22].title)).assertIsDisplayed()
        device.pressBack()
        waitForCard(22)
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        assertEquals(before, cardOffsetInViewport(cardTag), 2f)
        tab("Settings")
        tab("Search")
        waitForCard(22)
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        scenario.recreate()
        compose.waitUntil(10_000) { compose.onNodeWithTag(cardTag).isDisplayed() }
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
        activate(compose.onNodeWithTag("Search query input"))
        activate(compose.onNodeWithContentDescription("Pixiv"))
        addSearchTag("landscape")
        activate(compose.onNodeWithText("Apply"))
        waitForCard(0)
        val applied = container.registry.requests.single { it.source == SourceKey.PIXIV && it.pageToken == null }.query
        val queryHash = QueryHash.from(applied)
        scrollToFirstVisibleCard(6)
        val cardTag = searchCardTestTag(posts.getValue(SourceKey.PIXIV)[6].id)
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        val offset = cardOffsetInViewport(cardTag)
        val expectedScroll = SearchScrollState(6, -offset.roundToInt())
        assertTrue("A real drag should retain its nonzero pixel offset", expectedScroll.firstVisibleItemOffsetPx > 0)
        compose.waitUntil(10_000) {
            io { container.data.uiRestoreRepository.getSearchScrollState(queryHash) == expectedScroll }
        }
        val savedScroll = requireNotNull(io { container.data.uiRestoreRepository.getSearchScrollState(queryHash) })
        val previousDatabase = container.database
        val previousSettings = container.settings

        coldGraphRelaunch()

        org.junit.Assert.assertNotSame(previousDatabase, container.database)
        org.junit.Assert.assertNotSame(previousSettings, container.settings)
        compose.waitUntil(10_000) { compose.onNodeWithTag(cardTag).isDisplayed() }
        compose.onNodeWithText("Pixiv · landscape").assertIsDisplayed()
        compose.onNodeWithTag(cardTag).assertIsDisplayed()
        assertEquals(offset, cardOffsetInViewport(cardTag), 2f)
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
        activate(compose.onNodeWithText("Searches"))
        activate(compose.onNodeWithTag("Recent search:journey:multi"))
        waitForCard(0)
        val multiRequests = container.registry.requests.filter { it.pageToken == null }
        assertEquals(setOf(SourceKey.GELBOORU, SourceKey.PIXIV), multiRequests.map { it.source }.toSet())
        assertTrue(multiRequests.all { it.query.includeTags == listOf("multi") && it.query.sort == SortMode.POPULAR })
        assertTrue(io { container.data.queryRepository.observeAppliedQuery("unified").first() == null })
        container.registry.requests.clear()
        tab("Recents")
        activate(compose.onNodeWithText("FYP"))
        activate(compose.onNodeWithTag("Recent search:for_you:journey"))
        compose.waitUntil(10_000) { container.registry.requests.count { it.pageToken == null } >= 2 }
        val replay = container.registry.requests.filter { it.pageToken == null }
        assertEquals(sourceTags, replay.associate { it.source to it.query.includeTags })
        assertTrue(replay.all { it.query.sort == SortMode.TOP })
        assertTrue(io { container.data.likesRepository.observeLikes("profile-main").first().isEmpty() })
        compose.onNodeWithTag(searchCardTestTag(posts.getValue(SourceKey.PIXIV)[0].id)).assertIsDisplayed()
    }

    private fun addSearchTag(value: String) {
        val input = compose.onNodeWithTag("Search query input")
        compose.waitUntil(10_000) {
            input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty()
        }
        activate(input)
        input.performTextInput(value)
        val add = hasText("Add", substring = false) and isEnabled() and hasClickAction()
        compose.waitUntil(10_000) {
            input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text == value &&
                compose.onAllNodes(add).fetchSemanticsNodes().isNotEmpty()
        }
        input.performImeAction()
        compose.waitUntil(10_000) {
            input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty() &&
                compose.onAllNodes(hasText(value, substring = false) and hasClickAction())
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The tagged grid wrapper receives touches; its nested lazy grid owns scroll semantics. */
    private fun scrollToFirstVisibleCard(index: Int) {
        val grid = compose.onNodeWithTag("Search results", useUnmergedTree = true)
        repeat(24) {
            val viewport = grid.fetchSemanticsNode().boundsInRoot
            val target = visibleCardBounds(index, viewport)
            val previous = if (index > 0) visibleCardBounds(index - 1, viewport) else null
            // Fixture cards have equal heights. The even target starts its row once the preceding
            // card has left the viewport, leaving a real, nonzero persisted offset to restore.
            if (target != null && previous == null) {
                compose.onNodeWithTag(searchCardTestTag(posts.getValue(SourceKey.PIXIV)[index].id)).assertIsDisplayed()
                return
            }
            val distance = if (target == null) viewport.height * 0.45f else {
                (target.top - viewport.top + viewport.width * 0.08f).coerceAtMost(viewport.height * 0.45f)
            }
            grid.performTouchInput {
                val start = Offset(centerX, height * 0.8f)
                swipeWithVelocity(start, start - Offset(0f, distance), endVelocity = 0f, durationMillis = 600L)
            }
            compose.waitForIdle()
        }
        throw AssertionError("Search card $index did not become the first visible fixture row after 24 drags")
    }

    private fun visibleCardBounds(index: Int, viewport: Rect): Rect? {
        val tag = searchCardTestTag(posts.getValue(SourceKey.PIXIV)[index].id)
        return compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            ?.takeIf { bounds -> bounds.height > 0f && bounds.bottom > viewport.top && bounds.top < viewport.bottom }
    }

    private fun cardOffsetInViewport(tag: String): Float {
        val cardTop = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
        val viewportTop = compose.onNodeWithTag("Search results", useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.y
        return cardTop - viewportTop
    }

    private fun waitForCard(index: Int) {
        val tag = searchCardTestTag(posts.getValue(SourceKey.PIXIV)[index].id)
        compose.waitUntil(10_000) { compose.onNodeWithTag(tag).isDisplayed() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun query(tags: List<String>, sort: SortMode) = Query(
        QueryMode.Unified, tags, emptyList(), sort, null, null,
    )
}
