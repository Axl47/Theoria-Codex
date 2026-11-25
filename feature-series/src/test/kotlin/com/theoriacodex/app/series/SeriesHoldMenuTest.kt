package com.theoriacodex.app.series

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.theoriacodex.data.repository.DownloadChoice
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.extensionSourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
class SeriesHoldMenuTest {
    @get:Rule val compose = createComposeRule()

    private val own = FakeSeriesSource(SeriesKind.MANGA.extensionSourceKey(1).toString(), "Ink Harbor", "en", count = 1)
    private val other = FakeSeriesSource(SeriesKind.MANGA.extensionSourceKey(2).toString(), "Paper Moon", "en", count = 1)
    private val followed = FollowedSeries(Series(SeriesId(own.key, "/tide"), "Tide Atlas"), unreadCount = 1, started = true)

    @Test
    fun theHoldMenuOffersThePageOverflowActionsPlusDownload() {
        val entries = listOf(entry("/1", read = true), entry("/2", read = false))
        val overflow = SeriesPageState(entries = entries, refreshing = false).actions(canSwitchSource = true).items
        assertEquals(overflow, followed.actions(listOf(own, other), checking = false).items)

        val downloads = mutableListOf<Pair<SeriesId, DownloadChoice>>()
        val viewModel = SeriesTabViewModel(StubSeriesRepository(listOf(followed)), { listOf(own, other) }, download = { id, choice -> downloads += id to choice }) { _, _ -> NOTHING_CHECKED }
        compose.setContent {
            MaterialTheme { SeriesTabScreen(viewModel, onOpenSeries = {}, onOpenExtensions = {}, switchSource = { error("not opened") }) }
        }
        compose.onNodeWithText(followed.series.title).performTouchInput { longClick() }

        (overflow.map { it.label } + DOWNLOAD).forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText(DOWNLOAD).performClick()
        compose.onNodeWithText("All unread").performClick()
        compose.waitForIdle()
        assertEquals(listOf(followed.series.id to DownloadChoice.ALL_UNREAD), downloads)
    }

    private fun entry(address: String, read: Boolean) =
        StoredSeriesEntry(SeriesEntry(address, address), sourceOrder = 0, firstSeenAtEpochMs = 0, read = read)
}

internal val NO_HOLD = SeriesHold(
    actions = { it.actions(null, checking = false) },
    canDownload = { false },
    onMarkAllRead = { _, _ -> },
    onRefresh = {},
    onDownload = { _, _ -> },
)
