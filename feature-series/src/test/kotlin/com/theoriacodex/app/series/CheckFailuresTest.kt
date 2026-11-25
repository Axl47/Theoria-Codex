package com.theoriacodex.app.series

import android.app.Application
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
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
    fun failuresGroupBySourceThenReasonAndDropSeriesNoLongerFollowed_adr_0219() {
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
    fun theFailureCardOpensTheListRetriesAGroupAndDismisses_adr_0219() {
        val reachable = failure("/a")
        val limited = failure("/b", reason = SourceFailureReason.RATE_LIMITED)
        val content = SeriesTabState(libraryLoaded = true).content(listOf(reachable, limited).map { FollowedSeries(it.series, unreadCount = 0) })
        var failed by mutableStateOf<FollowedCheck?>(FollowedCheck(emptyList(), checked = 3, failures = listOf(reachable, limited)))
        val retried = mutableListOf<Set<SeriesId>>()
        compose.setContent {
            MaterialTheme {
                FollowingBody(
                    content = content, checking = null, found = emptyList(), failedCheck = failed, grid = rememberLazyGridState(),
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
}
