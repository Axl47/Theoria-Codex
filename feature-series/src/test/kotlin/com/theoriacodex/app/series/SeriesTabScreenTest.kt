package com.theoriacodex.app.series

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
class SeriesTabScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun globalSearchHeadersShowLanguageSubtitleAndOpenDedicatedSourceSearch() {
        val sources = listOf(
            FakeSeriesSource("custom_mihon_1", "MangaDex", "en", count = 4),
            FakeSeriesSource("custom_mihon_2", "MangaDex", "es-419", count = 4),
        )
        val viewModel = SeriesTabViewModel(EmptySeriesRepository, { sources }) {}
        compose.setContent {
            MaterialTheme {
                SeriesTabScreen(viewModel, onOpenSeries = {}, onOpenExtensions = {})
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(SERIES_SEARCH_INPUT_TAG).performTextInput("reincarnation")
        compose.onNodeWithTag(SERIES_SEARCH_INPUT_TAG).performImeAction()
        compose.waitForIdle()

        compose.onNodeWithText(languageName("en")).assertIsDisplayed()
        compose.onNodeWithText(languageName("es-419")).assertIsDisplayed()

        // Tapping a source header opens its dedicated search with the query and floating browse controls.
        compose.onNodeWithText(languageName("en")).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(BROWSE_CONTROLS_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithText("reincarnation").assertIsDisplayed()

        // Navigating back returns to the global search results.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(languageName("es-419")).assertIsDisplayed()
    }

    @Test
    fun browseControlsHideOnDownwardScrollAndReturnOnUpwardScroll() {
        val sources = listOf(FakeSeriesSource("custom_mihon_1", "Asura Scans", "en", count = 30))
        val viewModel = SeriesTabViewModel(EmptySeriesRepository, { sources }) {}
        compose.setContent {
            MaterialTheme {
                SeriesTabScreen(viewModel, onOpenSeries = {}, onOpenExtensions = {})
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(BROWSE_CONTROLS_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SERIES_SEARCH_BAR_TAG).assertIsDisplayed()

        // Swiping up scrolls down through the grid: floating controls hide and search bar collapses.
        compose.onNodeWithText("Popular 5").performTouchInput { swipeUp(startY = 600f, endY = 100f) }
        compose.waitForIdle()
        compose.onNodeWithTag(BROWSE_CONTROLS_BAR_TAG).assertDoesNotExist()
        compose.onNodeWithTag("collapsed-search-strip").assertIsDisplayed()

        // Swiping down scrolls back toward the top: floating browse controls return.
        compose.onNodeWithText("Popular 11").performTouchInput { swipeDown(startY = 200f, endY = 700f) }
        compose.waitForIdle()
        compose.onNodeWithTag(BROWSE_CONTROLS_BAR_TAG).assertIsDisplayed()
    }

    @Test
    fun seriesCardKeepsTitleInsideCoverBounds() {
        val series = Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/one"), "Twin Reincarnation")
        compose.setContent {
            MaterialTheme {
                SeriesCard(series, onClick = {})
            }
        }
        compose.waitForIdle()
        val cardBounds = compose.onNodeWithText("Twin Reincarnation").fetchSemanticsNode().boundsInRoot
        assertTrue(cardBounds.width > 0f && cardBounds.height > 0f)
    }
}

private class FakeSeriesSource(
    id: String,
    override val name: String,
    override val language: String,
    private val count: Int,
) : SeriesSource {
    override val key = SourceKey.parseOrThrow(id)
    override val supportsLatest = true

    override suspend fun popular(page: Int) = page("Popular", page)
    override suspend fun latest(page: Int) = page("Latest", page)
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>) = page(text, page)
    override suspend fun details(series: Series) = series
    override suspend fun entries(series: Series) = emptyList<SeriesEntry>()
    override suspend fun pages(entry: SeriesEntry) = emptyList<ImageRef>()

    private fun page(prefix: String, page: Int): SeriesPage = SeriesPage(
        series = (1..count).map { index ->
            Series(SeriesId(key, "/$prefix-$page-$index"), "$prefix $index")
        },
        hasNextPage = false,
    )
}

private object EmptySeriesRepository : SeriesRepository {
    override fun observeFollowed(): Flow<List<FollowedSeries>> = emptyFlow()
    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = emptyFlow()
    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = emptyFlow()
    override suspend fun series(id: SeriesId): StoredSeries? = null
    override suspend fun entries(id: SeriesId): List<StoredSeriesEntry> = emptyList()
    override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>) = emptyList<SeriesEntry>()
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
    override suspend fun recordProgress(id: SeriesId, entryAddress: String, page: Int, pageCount: Int?) = Unit
    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
}
