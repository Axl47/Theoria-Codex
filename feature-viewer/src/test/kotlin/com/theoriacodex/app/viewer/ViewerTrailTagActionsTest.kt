package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.theoriacodex.app.tags.PostTagContextProvider
import com.theoriacodex.app.tags.PostTagSavedTags
import com.theoriacodex.app.testing.waitUntilSettled
import com.theoriacodex.app.ui.navigation.LocalTrailOpener
import com.theoriacodex.app.ui.navigation.TrailOpener
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerTrailTagActionsTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<TrailRequest>()
    private val crumbTaps = mutableListOf<Int>()
    private val removed = mutableListOf<Int>()
    private val saves = mutableListOf<String>()
    private val forks = ViewerTrailForks()
    private val levelQuery = Query(QueryMode.Source(SourceKey.DANBOORU), listOf("aurora", "landscape"), emptyList(), SortMode.NEWEST, null, null)
    private val shown = post("p", SourceKey.DANBOORU).copy(canonicalTags = listOf("mountain"))
    private val crumbs = listOf(
        ViewerCrumb("landscape"),
        ViewerCrumb("night sky", "tag:DANBOORU:night_sky", CrumbTag("night_sky", SourceKey.DANBOORU)),
        ViewerCrumb("aurora", "tag:DANBOORU:aurora", CrumbTag("aurora", SourceKey.DANBOORU)),
    )

    private fun showRow() = compose.setContent {
        PostTagContextProvider(
            state = null,
            savedTags = PostTagSavedTags(flowOf(emptySet()), add = { _, tag -> saves += tag }),
            tagSuggestions = { _, input -> listOf(TagSuggestion("${input}ry_sky", null, 48_000)) },
        ) {
            CompositionLocalProvider(LocalTrailOpener provides TrailOpener { request -> opened += request; true }) {
                ViewerTrailRow(
                    shown, emptyList(), ViewerTrail(crumbs, levelQuery, { crumbTaps += it }, { removed += it }),
                    emptySet(), forks, {},
                )
            }
        }
    }

    @Test
    fun `holding a step opens its menu without going back to it, and Remove removes that step (ADR 0217)`() {
        showRow()
        compose.onNodeWithTag("viewer-trail-crumb-1").performTouchInput { longClick() }
        compose.onNodeWithText("Save").assertExists()
        compose.onNodeWithText("Remove from trail").performClick()

        assertEquals(emptyList<Int>(), crumbTaps)
        assertEquals(listOf(1), removed)
    }

    @Test
    fun `holding a suggested tag offers Save without starting a trail`() {
        showRow()
        compose.onNodeWithTag("viewer-trail-chip-tag:mountain").performTouchInput { longClick() }
        compose.onNodeWithText("Save").performClick()

        assertEquals(emptyList<TrailRequest>(), opened)
        assertEquals(listOf("mountain"), saves)
    }

    @Test
    fun `a tag picked from the add sheet opens as the next step of this level (ADR 0217)`() {
        showRow()
        compose.onNodeWithContentDescription("Add tag").performClick()
        compose.onNodeWithTag("trail-tag-field").performTextInput("star")
        compose.waitUntilSettled(5_000) { compose.onAllNodesWithTagCount("trail-tag-suggestion-starry_sky") > 0 }
        compose.onNodeWithTag("trail-tag-suggestion-starry_sky").performClick()
        compose.waitUntilSettled(5_000) { opened.isNotEmpty() }

        assertEquals(listOf(TrailRequest.Tag("starry_sky", SourceKey.DANBOORU, levelQuery, shown)), opened)
    }

    @Test
    fun `an open step menu keeps the chrome from hiding until it closes (ADR 0217)`() {
        showRow()
        compose.onNodeWithTag("viewer-trail-crumb-1").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(1, forks.chromeHolds)

        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(0, forks.chromeHolds)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
