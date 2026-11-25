package com.theoriacodex.app.series

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.theoriacodex.data.repository.CheckProgress
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun pullingSaysWhatTheCheckLooksForAndArmsAtTheThreshold_adr_0218() {
        val short = refreshPill(null, emptyList(), 0.6f, EntryWords.CHAPTERS) as RefreshPill.Pulling
        assertEquals("Check for new chapters", short.label)
        assertFalse(short.armed)
        assertFalse((refreshPill(null, emptyList(), 1f, EntryWords.CHAPTERS) as RefreshPill.Pulling).armed)
        assertTrue((refreshPill(null, emptyList(), 1.2f, EntryWords.CHAPTERS) as RefreshPill.Pulling).armed)
        assertEquals("Check for new episodes", (refreshPill(null, emptyList(), 0.5f, EntryWords.EPISODES) as RefreshPill.Pulling).label)
        assertEquals("Check for updates", (refreshPill(null, emptyList(), 0.5f, null) as RefreshPill.Pulling).label)
    }

    @Test
    fun thePullNamesWhatTheCheckCoversWhicheverKindIsFiltered_adr_0218() {
        val manga = FollowedSeries(update("custom_mihon_1", "/a", 0).series, unreadCount = 0)
        val anime = FollowedSeries(update("custom_aniyomi_1", "/c", 0).series, unreadCount = 0)
        assertNull(SeriesTabState(kind = SeriesKind.MANGA).content(listOf(manga, anime)).checkWords)
        assertEquals(EntryWords.EPISODES, SeriesTabState().content(listOf(anime)).checkWords)
    }

    @Test
    fun aRunningCheckShowsItsProgressAndAFinishedOneOnlyWhatWasNew_adr_0218() {
        assertEquals(RefreshPill.Checking(CheckProgress(12, 40)), refreshPill(CheckProgress(12, 40), emptyList(), 1f, EntryWords.CHAPTERS))
        assertNull(refreshPill(null, emptyList(), 0f, EntryWords.CHAPTERS))
        val found = listOf(update("custom_mihon_1", "/a", 2), update("custom_mihon_1", "/b", 1), update("custom_aniyomi_1", "/c", 1))
        assertEquals(RefreshPill.Found("3 new chapters · 1 new episode"), refreshPill(null, found, 0f, null))
    }

    @Test
    fun theCheckingPillShowsHowManySeriesAreCheckedWithAMatchingBar() {
        compose.setContent { MaterialTheme { RefreshPillView(RefreshPill.Checking(CheckProgress(12, 40))) } }

        compose.onNodeWithText("12 of 40 series checked").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.3f, 0f..1f))).assertIsDisplayed()
    }

    private fun update(source: String, path: String, count: Int) = SeriesUpdate(
        Series(SeriesId(SourceKey.parseOrThrow(source), path), path),
        (1..count).map { SeriesEntry("$path/$it", "Chapter $it") },
    )
}
