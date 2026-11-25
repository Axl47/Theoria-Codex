package com.theoriacodex.app.series

import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.Box
import android.app.Application
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.data.repository.FollowedCheck
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
class CheckFailuresTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun failuresGroupBySourceThenReasonAndDropSeriesNoLongerFollowed_adr_0220() {
        val unreachable = failure("/a")
        val parse = failure("/b", reason = SourceFailureReason.PARSE)
        val slow = failure("/c", reason = SourceFailureReason.TIMED_OUT)
        val elsewhere = failure("/d", source = "custom_mihon_2", reason = SourceFailureReason.BLOCKED)
        val check = FollowedCheck(emptyList(), checked = 5, failures = listOf(parse, unreachable, slow, elsewhere))
        val followed = setOf(unreachable, parse, slow).mapTo(HashSet()) { it.series.id }

        assertEquals(
            listOf(
                SourceFailures(
                    SourceKey.parseOrThrow("custom_mihon_1"),
                    "Ink Harbor",
                    listOf(
                        ProblemGroup(CheckProblem.UNREACHABLE, listOf(unreachable.series)),
                        ProblemGroup(CheckProblem.TIMED_OUT, listOf(slow.series)),
                        ProblemGroup(CheckProblem.SOURCE_ERROR, listOf(parse.series)),
                    ),
                ),
            ),
            check.stillFollowed(followed).bySource { if (it.id == "custom_mihon_1") "Ink Harbor" else null },
        )
        assertEquals(listOf("custom_mihon_1", "custom_mihon_2"), check.bySource { null }.map(SourceFailures::name))
    }

    @Test
    fun theFailureCardOpensTheListRetriesAGroupAndDismisses_adr_0220() {
        val reachable = failure("/a")
        val limited = failure("/b", reason = SourceFailureReason.RATE_LIMITED)
        val content = SeriesTabState(libraryLoaded = true).content(listOf(reachable, limited).map { FollowedSeries(it.series, unreadCount = 0) })
        var failed by mutableStateOf<FollowedCheck?>(FollowedCheck(emptyList(), checked = 3, failures = listOf(reachable, limited)))
        val retried = mutableListOf<Set<SeriesId>>()
        compose.setContent {
            MaterialTheme {
                FollowingBody(
                    content = content, checking = null, found = emptyList(), failedCheck = failed, checkRun = 0, grid = rememberLazyGridState(),
                    onRefresh = {}, onRetryFailed = { retried += it }, onDismissFailures = { failed = null }, onUnfollow = {},
                    onChooseKind = {}, onChooseLibrary = {}, onShowAll = {}, onSearchAll = {}, onBrowse = {}, onOpenSeries = {},
                )
            }
        }

        compose.onNodeWithText("Couldn't check 2 series").performClick()
        compose.onNodeWithText(COULDNT_CHECK).assertExists()
        compose.onNodeWithText("Couldn't reach site").assertExists()
        compose.onAllNodesWithText("Retry").onLast().performClick()
        assertEquals(listOf(setOf(limited.series.id)), retried)

        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.onAllNodesWithText("Couldn't check 2 series").assertCountEquals(0)
    }

    @Test
    fun aFailedSeriesWithAnotherSourceOfItsKindOffersSwitchSource_adr_0220() {
        val stuck = failure("/a")
        val sources = listOf(FakeSeriesSource("custom_mihon_1", "Ink Harbor", "en", count = 1), FakeSeriesSource("custom_mihon_3", "Paper Lantern", "en", count = 1))
        val content = SeriesTabState(sources = sources, libraryLoaded = true).content(listOf(FollowedSeries(stuck.series, unreadCount = 0)))
        val tools = CheckFailureTools(switchSource = { id -> SwitchSourceViewModel(id, { sources }, store = {}) { _, _ -> false } })
        compose.setContent {
            MaterialTheme {
                FollowingBody(
                    content = content, checking = null, found = emptyList(), failedCheck = FollowedCheck(emptyList(), checked = 2, failures = listOf(stuck)), checkRun = 0,
                    grid = rememberLazyGridState(), onRefresh = {}, onRetryFailed = {}, onDismissFailures = {}, onUnfollow = {},
                    onChooseKind = {}, onChooseLibrary = {}, onShowAll = {}, onSearchAll = {}, onBrowse = {}, onOpenSeries = {}, failureTools = tools,
                )
            }
        }

        compose.onNodeWithText("Couldn't check 1 series").performClick()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText(SWITCH_SOURCE).performClick()
        compose.waitUntil { compose.onAllNodesWithText("Paper Lantern", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(COULDNT_CHECK).assertCountEquals(0)
    }

    @Test
    fun aCheckThatFinishesWithTheCardOffScreenOffersToScrollToIt_adr_0220() {
        val stuck = failure("/a")
        val followed = listOf(FollowedSeries(stuck.series, unreadCount = 0)) + (1..40).map { FollowedSeries(failure("/s$it").series, unreadCount = 0) }
        val content = SeriesTabState(libraryLoaded = true).content(followed)
        val snackbar = SnackbarHostState()
        var failed by mutableStateOf<FollowedCheck?>(null)
        var runs by mutableIntStateOf(0)
        lateinit var grid: LazyGridState
        compose.setContent {
            grid = rememberLazyGridState()
            MaterialTheme {
                Box {
                    FollowingBody(
                        content = content, checking = null, found = emptyList(), failedCheck = failed, checkRun = runs, grid = grid,
                        onRefresh = {}, onRetryFailed = {}, onDismissFailures = {}, onUnfollow = {},
                        onChooseKind = {}, onChooseLibrary = {}, onShowAll = {}, onSearchAll = {}, onBrowse = {}, onOpenSeries = {},
                        failureTools = CheckFailureTools(snackbar = snackbar),
                    )
                    SnackbarHost(snackbar)
                }
            }
        }
        val finish = {
            failed = FollowedCheck(emptyList(), checked = 41, failures = listOf(stuck))
            runs++
        }

        compose.runOnIdle(finish)
        compose.onNodeWithText("Couldn't check 1 series").assertIsDisplayed()
        compose.onAllNodesWithText(offScreenFailuresLine(1)).assertCountEquals(0)

        compose.runOnIdle { runBlocking { grid.scrollToItem(30) } }
        compose.runOnIdle(finish)
        compose.onNodeWithText(offScreenFailuresLine(1)).assertIsDisplayed()
        compose.onNodeWithText("Show").performClick()
        compose.onNodeWithText("Couldn't check 1 series").assertIsDisplayed()
    }
}
