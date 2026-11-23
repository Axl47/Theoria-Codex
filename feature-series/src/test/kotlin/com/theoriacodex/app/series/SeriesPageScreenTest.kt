package com.theoriacodex.app.series

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.theoriacodex.app.ui.components.LocalPostMenuActions
import com.theoriacodex.app.ui.components.PostMenuActions
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
import com.theoriacodex.domain.series.SeriesStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
class SeriesPageScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun heroActionsOpenBrowserShareAndExpandGenres() {
        val openedUrls = mutableListOf<String>()
        val sharedUrls = mutableListOf<String>()
        val harness = pageHarness(chapterCount = 3)
        compose.setContent {
            CompositionLocalProvider(
                LocalPostMenuActions provides PostMenuActions(
                    openInBrowser = { openedUrls += it },
                    share = { sharedUrls += it },
                ),
            ) {
                MaterialTheme {
                    SeriesPageScreen(
                        viewModel = harness.page,
                        onBack = {},
                        onOpenEntry = {},
                        onOpenExtensions = {},
                        switchSource = harness.switchSource,
                        onOpenSeries = {},
                        onSwitched = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Suicidal Battle God").assertIsDisplayed()
        compose.onNodeWithText("Asura Scans").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open in browser").performClick()
        compose.onNodeWithContentDescription("Share").performClick()
        assertEquals(listOf("https://asura.example/series/1"), openedUrls)
        assertEquals(listOf("https://asura.example/series/1"), sharedUrls)

        // Genres reveal when expanding the About section.
        compose.onNodeWithText("Fantasy").assertDoesNotExist()
        compose.onNodeWithText("More").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fantasy").assertIsDisplayed()
        compose.onNodeWithText("Less").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fantasy").assertDoesNotExist()
    }

    @Test
    fun topBarSearchSortFilterAndOverflowActionsWork() {
        val harness = pageHarness(chapterCount = 4, readIndices = setOf(0))
        compose.setContent {
            MaterialTheme {
                SeriesPageScreen(
                    viewModel = harness.page,
                    onBack = {},
                    onOpenEntry = {},
                    onOpenExtensions = {},
                    switchSource = harness.switchSource,
                    onOpenSeries = {},
                    onSwitched = {},
                )
            }
        }
        compose.waitForIdle()

        // Search icon filters the chapter list inline.
        compose.onNodeWithContentDescription("Search chapters").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CHAPTER_SEARCH_INPUT_TAG).performTextInput("3")
        compose.waitForIdle()
        compose.onNodeWithText("Chapter 3").assertIsDisplayed()
        compose.onNodeWithText("Chapter 1").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close chapter search").performClick()
        compose.waitForIdle()

        // Filter sheet switches to Unread.
        compose.onNodeWithContentDescription("Sort and filter chapters").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Unread").performClick()
        compose.onNodeWithText("Chapter number").performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("3 of 4 chapters").assertIsDisplayed()

        // Overflow menu lists Mark all as read, Mark all as unread, Refresh, and Switch source.
        compose.onNodeWithContentDescription("More options").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Mark all as read").assertIsDisplayed()
        compose.onNodeWithText("Mark all as unread").assertIsDisplayed()
        compose.onNodeWithText("Refresh").assertIsDisplayed()
        compose.onNodeWithText(SWITCH_SOURCE).assertIsDisplayed()
        compose.onNodeWithText("Mark all as read").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), harness.repository.markedAll)
    }

    @Test
    fun topBarHidesOnDownwardScrollAndReturnsOnUpwardScroll() {
        val harness = pageHarness(chapterCount = 30)
        compose.setContent {
            MaterialTheme {
                SeriesPageScreen(
                    viewModel = harness.page,
                    onBack = {},
                    onOpenEntry = {},
                    onOpenExtensions = {},
                    switchSource = harness.switchSource,
                    onOpenSeries = {},
                    onSwitched = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SERIES_TOP_BAR_TAG).assertIsDisplayed()

        compose.onNodeWithText("Chapter 28").performTouchInput { swipeUp(startY = 700f, endY = 100f) }
        compose.waitForIdle()
        compose.onNodeWithTag(SERIES_TOP_BAR_TAG).assertDoesNotExist()

        compose.onNodeWithText("Chapter 20").performTouchInput { swipeDown(startY = 200f, endY = 700f) }
        compose.waitForIdle()
        compose.onNodeWithTag(SERIES_TOP_BAR_TAG).assertIsDisplayed()
    }
}

private class PageHarness(
    val page: SeriesPageViewModel,
    val switchSource: SwitchSourceViewModel,
    val repository: PageMemoryRepository,
)

private fun pageHarness(chapterCount: Int, readIndices: Set<Int> = emptySet()): PageHarness {
    val primary = PageFakeSource("custom_mihon_1", "Asura Scans", "https://asura.example")
    val alternate = PageFakeSource("custom_mihon_2", "MangaDex", "https://mangadex.example")
    val id = SeriesId(primary.key, "/series/1")
    val series = Series(
        id = id,
        title = "Suicidal Battle God",
        author = "Blue-Deep",
        genres = listOf("Fantasy", "Action"),
        description = "A time travel action fantasy of the strongest of mankind.",
        status = SeriesStatus.ONGOING,
    )
    val entries = (chapterCount downTo 1).mapIndexed { order, number ->
        StoredSeriesEntry(
            entry = SeriesEntry("/c/$number", "Chapter $number", number = number.toFloat(), uploadedAtEpochMs = number * 1000L),
            sourceOrder = order,
            firstSeenAtEpochMs = 1L,
            read = order in readIndices,
        )
    }
    val repository = PageMemoryRepository(StoredSeries(series, followed = true), entries)
    val sources = listOf(primary, alternate)
    val page = SeriesPageViewModel(id, repository, loadSources = { sources })
    val switchSource = SwitchSourceViewModel(id, loadSources = { sources }, store = {}, switch = { _, _ -> false })
    return PageHarness(page, switchSource, repository)
}

private class PageFakeSource(id: String, override val name: String, private val baseUrl: String) : SeriesSource {
    override val key = SourceKey.parseOrThrow(id)
    override val language = "en"
    override suspend fun popular(page: Int) = SeriesPage(emptyList(), false)
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>) = SeriesPage(emptyList(), false)
    override suspend fun details(series: Series) = series
    override suspend fun entries(series: Series) = emptyList<SeriesEntry>()
    override suspend fun pages(entry: SeriesEntry) = emptyList<ImageRef>()
    override fun webUrl(series: Series): String = "$baseUrl${series.id.address}"
}

private class PageMemoryRepository(stored: StoredSeries, entries: List<StoredSeriesEntry>) : SeriesRepository {
    private val storedFlow = MutableStateFlow<StoredSeries?>(stored)
    private val entriesFlow = MutableStateFlow(entries)
    val markedAll = mutableListOf<Boolean>()

    override fun observeFollowed(): Flow<List<FollowedSeries>> = emptyFlow()
    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = storedFlow
    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = entriesFlow
    override suspend fun series(id: SeriesId): StoredSeries? = storedFlow.value
    override suspend fun entries(id: SeriesId): List<StoredSeriesEntry> = entriesFlow.value
    override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>) = emptyList<SeriesEntry>()
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) {
        storedFlow.value = storedFlow.value?.copy(followed = followed)
    }

    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
    override suspend fun setAllRead(id: SeriesId, read: Boolean) {
        markedAll += read
        entriesFlow.value = entriesFlow.value.map { it.copy(read = read, lastPage = if (read) it.lastPage else 0) }
    }

    override suspend fun recordProgress(id: SeriesId, entryAddress: String, page: Int, pageCount: Int?) = Unit
    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
}
