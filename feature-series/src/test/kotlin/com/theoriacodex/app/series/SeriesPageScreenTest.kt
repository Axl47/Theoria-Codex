package com.theoriacodex.app.series

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
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
import com.theoriacodex.app.ui.undo.ProvideUndoSlot
import com.theoriacodex.app.ui.undo.UndoSlot
import com.theoriacodex.data.repository.EntryProgressRestore
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntries
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.SeriesStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun heroActionsOpenBrowserShareCoverLightboxAndGenreSearch() {
        val openedUrls = mutableListOf<String>()
        val sharedUrls = mutableListOf<String>()
        val searchedGenres = mutableListOf<String>()
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
                        onSearchGenre = { searchedGenres += it },
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Last Lantern Saga").assertIsDisplayed()
        compose.onNodeWithText("Ink Harbor").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open in browser").performClick()
        compose.onNodeWithContentDescription("Share").performClick()
        assertEquals(listOf("https://inkharbor.example/series/1"), openedUrls)
        assertEquals(listOf("https://inkharbor.example/series/1"), sharedUrls)

        compose.onNodeWithTag(COVER_LIGHTBOX_TAG).assertDoesNotExist()
        compose.onNodeWithTag(HERO_COVER_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(COVER_LIGHTBOX_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("Close cover").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(COVER_LIGHTBOX_TAG).assertDoesNotExist()

        compose.onNodeWithText("Fantasy").assertDoesNotExist()
        compose.onNodeWithText("More").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fantasy").assertIsDisplayed()
        compose.onNodeWithText("Fantasy").performClick()
        assertEquals(listOf("Fantasy"), searchedGenres)
        compose.onNodeWithText("Less").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fantasy").assertDoesNotExist()
    }

    @Test
    fun topBarSearchSortFilterPillsChapterHoldMenuAndUndoWork() {
        val harness = pageHarness(chapterCount = 4, readIndices = setOf(0))
        val undoSlot = UndoSlot()
        compose.setContent {
            ProvideUndoSlot(undoSlot) {
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

        compose.onNodeWithContentDescription("Search chapters").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CHAPTER_SEARCH_INPUT_TAG).performTextInput("3")
        compose.waitForIdle()
        compose.onNodeWithText("Chapter 3").assertIsDisplayed()
        compose.onNodeWithText("Chapter 1").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close chapter search").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Sort and filter chapters").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Unread").performClick()
        compose.onNodeWithText("Chapter number").performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("3 of 4 chapters").assertIsDisplayed()
        compose.onNodeWithText("Unread").assertIsDisplayed()
        compose.onNodeWithText("Chapter number ↓").assertIsDisplayed()
        compose.onNodeWithText("Unread").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("4 chapters").assertIsDisplayed()

        compose.onNodeWithText("Chapter 3").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Mark previous as read").performClick()
        compose.waitForIdle()
        assertTrue(harness.page.state.value.entries.last().read)
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertFalse(harness.page.state.value.entries.last().read)

        compose.onNodeWithContentDescription("More options").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Mark all as read").assertIsDisplayed()
        compose.onNodeWithText("Mark all as unread").assertIsDisplayed()
        compose.onNodeWithText("Refresh").assertIsDisplayed()
        compose.onNodeWithText(SWITCH_SOURCE).assertIsDisplayed()
        compose.onNodeWithText("Mark all as read").performClick()
        compose.waitForIdle()
        assertTrue(harness.page.state.value.entries.all { it.read })
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertFalse(harness.page.state.value.entries.last().read)
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

    @Test
    fun entryDetailIncludesTranslatorGroupAlongsideDateAndProgress() {
        val entry = StoredSeriesEntry(
            entry = SeriesEntry("/c/1", "Chapter 1", translatorGroup = "Driftwood", uploadedAtEpochMs = 1_700_000_000_000L),
            sourceOrder = 0,
            firstSeenAtEpochMs = 1L,
            position = 3,
            length = 12,
        )
        val detail = entryDetail(entry, SeriesKind.MANGA).orEmpty()
        assertTrue(detail.contains("Driftwood"))
        assertTrue(detail.contains("Page 3 of 12"))
    }
}

private class PageHarness(
    val page: SeriesPageViewModel,
    val switchSource: SwitchSourceViewModel,
    val repository: PageMemoryRepository,
)

private fun pageHarness(chapterCount: Int, readIndices: Set<Int> = emptySet()): PageHarness {
    val primary = PageFakeSource("custom_mihon_1", "Ink Harbor", "https://inkharbor.example")
    val alternate = PageFakeSource("custom_mihon_2", "Paper Lantern", "https://paperlantern.example")
    val id = SeriesId(primary.key, "/series/1")
    val series = Series(
        id = id,
        title = "Last Lantern Saga",
        author = "Ren Hollow",
        genres = listOf("Fantasy", "Action"),
        description = "A time travel action fantasy of the strongest of mankind.",
        status = SeriesStatus.ONGOING,
    )
    val entries = (chapterCount downTo 1).mapIndexed { order, number ->
        StoredSeriesEntry(
            entry = SeriesEntry("/c/$number", "Chapter $number", number = number.toFloat(), translatorGroup = "ScanGroup", uploadedAtEpochMs = number * 1000L),
            sourceOrder = order,
            firstSeenAtEpochMs = 1L,
            read = order in readIndices,
        )
    }
    val repository = PageMemoryRepository(StoredSeries(series, followed = true), entries)
    val sources = listOf(primary, alternate)
    val page = SeriesPageViewModel(id, repository, loadSources = { sources }, ioDispatcher = Dispatchers.Unconfined)
    val switchSource = SwitchSourceViewModel(id, loadSources = { sources }, store = {}, switch = { _, _ -> false })
    return PageHarness(page, switchSource, repository)
}

private class PageFakeSource(id: String, override val name: String, private val baseUrl: String) : SeriesSource {
    override val key = SourceKey.parseOrThrow(id)
    override val language = "en"
    override suspend fun popular(page: Int) = SeriesPage(emptyList(), false)
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>) = SeriesPage(emptyList(), false)
    override suspend fun details(series: Series) = series
    override suspend fun entries(series: Series, stored: List<SeriesEntry>) = SeriesEntries(emptyList())
    override suspend fun media(entry: SeriesEntry) = EntryMedia.Pages(emptyList())
    override fun webUrl(series: Series): String = "$baseUrl${series.id.address}"
}

private class PageMemoryRepository(stored: StoredSeries, entries: List<StoredSeriesEntry>) : SeriesRepository {
    private val storedFlow = MutableStateFlow<StoredSeries?>(stored)
    private val entriesFlow = MutableStateFlow(entries)

    override fun observeFollowed(): Flow<List<FollowedSeries>> = emptyFlow()
    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = storedFlow
    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = entriesFlow
    override suspend fun series(id: SeriesId): StoredSeries? = storedFlow.value
    override suspend fun entries(id: SeriesId): List<StoredSeriesEntry> = entriesFlow.value
    override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>, seriesMemo: String?) = emptyList<SeriesEntry>()
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) {
        storedFlow.value = storedFlow.value?.copy(followed = followed)
    }

    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
    override suspend fun setChaptersShareFirstPage(id: SeriesId) = Unit
    override suspend fun setAllRead(id: SeriesId, read: Boolean): EntryProgressRestore {
        val changed = entriesFlow.value.filter { if (read) !it.read else (it.read || it.position > 0) }
        val prevLast = storedFlow.value?.lastOpenedEntry
        val clearLast = !read && prevLast != null
        entriesFlow.value = entriesFlow.value.map { it.copy(read = read, position = if (read) it.position else 0) }
        if (clearLast) storedFlow.value = storedFlow.value?.copy(lastOpenedEntry = null)
        return EntryProgressRestore(changed, restoreLastOpened = clearLast, lastOpenedEntry = prevLast)
    }

    override suspend fun setEntriesRead(id: SeriesId, entryAddresses: List<String>, read: Boolean): EntryProgressRestore {
        val targets = entryAddresses.toHashSet()
        val changed = entriesFlow.value.filter { it.entry.address in targets && (if (read) !it.read else (it.read || it.position > 0)) }
        val prevLast = storedFlow.value?.lastOpenedEntry
        val clearLast = !read && prevLast in targets
        entriesFlow.value = entriesFlow.value.map { entry ->
            if (entry.entry.address !in targets) entry
            else entry.copy(read = read, position = if (read) entry.position else 0)
        }
        if (clearLast) storedFlow.value = storedFlow.value?.copy(lastOpenedEntry = null)
        return EntryProgressRestore(changed, restoreLastOpened = clearLast, lastOpenedEntry = prevLast)
    }

    override suspend fun restoreProgress(id: SeriesId, restore: EntryProgressRestore) {
        val byAddress = restore.entries.associateBy { it.entry.address }
        entriesFlow.value = entriesFlow.value.map { current ->
            val target = byAddress[current.entry.address] ?: return@map current
            current.copy(read = target.read, position = target.position, length = target.length)
        }
        if (restore.restoreLastOpened) storedFlow.value = storedFlow.value?.copy(lastOpenedEntry = restore.lastOpenedEntry)
    }

    override suspend fun recordProgress(id: SeriesId, entryAddress: String, position: Long, length: Long?) = Unit
    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
}
