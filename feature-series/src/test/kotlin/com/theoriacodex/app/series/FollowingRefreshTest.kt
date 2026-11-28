package com.theoriacodex.app.series

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.theoriacodex.data.repository.CheckFailure
import com.theoriacodex.data.repository.CheckProgress
import com.theoriacodex.data.repository.FollowedCheck
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FollowingRefreshTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pullingSaysWhatTheCheckLooksFor_adr_0219() {
        assertEquals("Check for new chapters", pullLabel(EntryWords.CHAPTERS))
        assertEquals("Check for new episodes", pullLabel(EntryWords.EPISODES))
        assertEquals("Check for updates", pullLabel(null))
    }

    @Test
    fun thePullNamesWhatTheCheckCovers_adr_0219() {
        val manga = FollowedSeries(update("custom_mihon_1", "/a", 0).series, unreadCount = 0)
        val anime = FollowedSeries(update("custom_aniyomi_1", "/c", 0).series, unreadCount = 0)
        assertEquals(EntryWords.CHAPTERS, SeriesTabState(kind = SeriesKind.MANGA).content(listOf(manga, anime)).checkWords)
        assertNull(SeriesTabState().content(listOf(manga, anime)).checkWords)
        assertEquals(EntryWords.EPISODES, SeriesTabState().content(listOf(anime)).checkWords)
    }

    @Test
    fun aFinishedCheckSaysHowManySeriesItRefreshedAndWhatWasNew_adr_0219() {
        assertEquals(RefreshStatus.Checking(CheckProgress(12, 40)), refreshStatus(CheckProgress(12, 40), null))
        assertNull(refreshStatus(null, null))
        val found = listOf(update("custom_mihon_1", "/a", 2), update("custom_mihon_1", "/b", 1), update("custom_aniyomi_1", "/c", 1))
        assertEquals(RefreshStatus.Done("Refreshed 5 series · 3 new chapters · 1 new episode"), refreshStatus(null, FollowedCheck(found, checked = 5)))
        assertEquals(RefreshStatus.Done("Refreshed 4 series"), refreshStatus(null, FollowedCheck(emptyList(), checked = 4)))
        val failure = CheckFailure(found.first().series, SourceFailureReason.NETWORK)
        assertEquals(RefreshStatus.Done("Refreshed 3 series"), refreshStatus(null, FollowedCheck(emptyList(), checked = 4, failures = listOf(failure))))
        assertNull(refreshStatus(null, FollowedCheck(emptyList(), checked = 1, failures = listOf(failure))))
    }

    @Test
    fun theCheckingPillShowsHowManySeriesAreCheckedWithAMatchingBar() {
        compose.setContent { MaterialTheme { RefreshStatusPill(RefreshStatus.Checking(CheckProgress(12, 40))) } }

        compose.onNodeWithText("12 of 40 series checked").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.3f, 0f..1f))).assertIsDisplayed()
    }

    private fun update(source: String, path: String, count: Int) = SeriesUpdate(
        Series(SeriesId(SourceKey.parseOrThrow(source), path), path),
        (1..count).map { SeriesEntry("$path/$it", "Chapter $it") },
    )
}
