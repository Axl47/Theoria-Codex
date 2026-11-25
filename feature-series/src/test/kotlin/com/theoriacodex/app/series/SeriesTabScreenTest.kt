package com.theoriacodex.app.series

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.languageName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.extensionSourceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
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
            FakeSeriesSource("custom_mihon_1", "Paper Lantern", "en", count = 4),
            FakeSeriesSource("custom_mihon_2", "Paper Lantern", "es-419", count = 4),
        )
        val viewModel = SeriesTabViewModel(EmptySeriesRepository, { sources }) { NOTHING_CHECKED }
        compose.setContent {
            MaterialTheme {
                SeriesTabScreen(viewModel, onOpenSeries = {}, onOpenExtensions = {}, missingExtensions = NoMissingActions)
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(SERIES_SEARCH_INPUT_TAG).performTextInput("reincarnation")
        compose.onNodeWithTag(SERIES_SEARCH_INPUT_TAG).performImeAction()
        compose.waitForIdle()

        compose.onNodeWithText(languageName("en")).assertIsDisplayed()
        compose.onNodeWithText(languageName("es-419")).assertIsDisplayed()

        compose.onNodeWithText(languageName("en")).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(LISTING_CONTROLS_TAG).assertIsDisplayed()
        compose.onNodeWithText("reincarnation").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(languageName("es-419")).assertIsDisplayed()
    }

    @Test
    fun aSourcesListingFiltersStayClearOfTheCollapsedSearch() {
        val sources = listOf(FakeSeriesSource("custom_mihon_1", "Paper Lantern", "en", count = 40))
        val viewModel = SeriesTabViewModel(EmptySeriesRepository, { sources }) { NOTHING_CHECKED }
        compose.setContent { MaterialTheme { SeriesTabScreen(viewModel, onOpenSeries = {}, onOpenExtensions = {}, missingExtensions = NoMissingActions) } }
        compose.waitForIdle()
        compose.runOnIdle { viewModel.openDedicatedSearch(sources.single().key) }
        compose.waitForIdle()

        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()

        val strip = compose.onNodeWithTag("collapsed-search-strip").fetchSemanticsNode().boundsInRoot
        val filter = compose.onNodeWithContentDescription(FILTER).fetchSemanticsNode().boundsInRoot
        assertTrue("$filter overlaps $strip", filter.right <= strip.left)
    }

    @Test
    fun missingExtensionsGroupTheSeriesByTheExtensionThatProvidesThemAndLeaveAsTheyResolve() {
        fun followed(id: Long, title: String) = FollowedSeries(Series(SeriesId(SeriesKind.MANGA.extensionSourceKey(id), "/$id"), title), unreadCount = 0)
        val repository = StubSeriesRepository(listOf(followed(1, "Loaded"), followed(2, "Kaiju"), followed(3, "Dandadan"), followed(9, "Blue Period")))
        val repos = FakeExtensionRepos().apply {
            offered = listOf(
                RepoExtension("https://repo.example", "pkg.dex", "Ink Dex", listOf("en"), "1.0", SeriesKind.MANGA, RepoExtension.Status.AVAILABLE, sources = listOf(2L, 3L).map(SeriesKind.MANGA::extensionSourceKey)),
            )
            problems = mapOf("https://down.example" to "Not found")
        }
        val device = FakeSeriesExtensions(emptyList())
        var loaded = listOf("custom_mihon_1")
        val viewModel = SeriesTabViewModel(repository, { loaded.map { FakeSeriesSource(it, "Ink Harbor", "en", count = 1) } }, device.revision) { NOTHING_CHECKED }
        val actions = MissingExtensionsActions({ ExtensionsViewModel(device, repos) }, { error("unused") }, onOpenRepos = {})
        compose.setContent { MaterialTheme { SeriesTabScreen(viewModel, onOpenSeries = {}, onOpenExtensions = {}, missingExtensions = actions) } }
        compose.waitForIdle()

        compose.onNodeWithText("3 series need their extension").assertIsDisplayed()
        compose.onNodeWithText(SHOW_MISSING).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("2 series").assertIsDisplayed()
        compose.onNodeWithText(unreadReposLine(1)).assertIsDisplayed()
        repos.problems = emptyMap()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(unreadReposLine(1)).assertDoesNotExist()
        compose.onNodeWithText(NOT_IN_YOUR_REPOS).assertIsDisplayed()
        compose.onAllNodesWithText("Blue Period").assertCountEquals(2)
        compose.onAllNodesWithText("Kaiju").assertCountEquals(1)
        compose.onNodeWithText("Ink Dex").performClick()
        compose.onAllNodesWithText("Kaiju").assertCountEquals(2)

        compose.onAllNodesWithContentDescription("More options").onLast().performClick()
        compose.onNodeWithText(UNFOLLOW).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(NOT_IN_YOUR_REPOS).assertDoesNotExist()
        compose.onAllNodesWithText("Blue Period").assertCountEquals(0)

        compose.onNodeWithText("Install").performClick()
        compose.waitForIdle()
        assertEquals(listOf("pkg.dex"), repos.installed)
        loaded = listOf("custom_mihon_1", "custom_mihon_2", "custom_mihon_3")
        device.revision.value++
        compose.waitForIdle()
        compose.onNodeWithText(MISSING_EXTENSIONS_TITLE).assertDoesNotExist()
        compose.onNodeWithText(SHOW_MISSING).assertDoesNotExist()
        assertEquals("1 series needs its extension", missingExtensionsLine(1))
    }

    @Test
    fun followingOpensFirstAndNarrowsByTitleAndFilter() {
        val source = SourceKey.parseOrThrow("custom_mihon_1")
        val followed = listOf(
            FollowedSeries(Series(SeriesId(source, "/alpha"), "Alpha Saga"), unreadCount = 2, started = true),
            FollowedSeries(Series(SeriesId(source, "/beta"), "Beta Tale"), unreadCount = 5),
        )
        val viewModel = SeriesTabViewModel(StubSeriesRepository(followed), { listOf(FakeSeriesSource("custom_mihon_1", "Ink Harbor", "en", count = 3)) }) { NOTHING_CHECKED }
        compose.setContent { MaterialTheme { SeriesTabScreen(viewModel, onOpenSeries = {}, onOpenExtensions = {}, missingExtensions = NoMissingActions) } }
        compose.waitForIdle()

        compose.onNodeWithTag(seriesViewTag(SeriesView.FOLLOWING)).assertIsSelected()
        compose.onNodeWithText("Alpha Saga").assertIsDisplayed()
        compose.onNodeWithTag(SERIES_SEARCH_INPUT_TAG).performTextInput("beta")
        compose.waitForIdle()
        compose.onNodeWithText("Alpha Saga").assertDoesNotExist()
        compose.onNodeWithText("Beta Tale").assertIsDisplayed()

        compose.onNodeWithContentDescription("Clear").performClick()
        compose.onNodeWithContentDescription(SORT_AND_FILTER).performClick()
        compose.onNodeWithText("Not started").performClick()
        compose.waitForIdle()
        assertEquals(LibraryShow.NOT_STARTED, viewModel.content.value.library.show)
        assertEquals(listOf("Beta Tale"), viewModel.content.value.following?.map { it.series.title })
        compose.onNodeWithContentDescription(SORT_AND_FILTER).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Filtered"))
    }

    @Test
    fun browsePicksItsSourceAndListingFromOneHeader() {
        val sources = listOf(FakeSeriesSource("custom_mihon_1", "Ink Harbor", "en", count = 3), FakeSeriesSource("custom_aniyomi_2", "Moon Reel", "en", count = 3))
        val viewModel = SeriesTabViewModel(EmptySeriesRepository, { sources }) { NOTHING_CHECKED }
        compose.setContent { MaterialTheme { SeriesTabScreen(viewModel, onOpenSeries = {}, onOpenExtensions = {}, missingExtensions = NoMissingActions) } }
        compose.waitForIdle()

        compose.onNodeWithTag(seriesViewTag(SeriesView.BROWSE)).assertIsSelected()
        compose.onNodeWithTag(BROWSED_SOURCE_TAG).assert(hasText("Ink Harbor", substring = true))
        compose.onNodeWithText("Latest").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Latest 1").assertIsDisplayed()

        compose.onNodeWithTag(BROWSED_SOURCE_TAG).performClick()
        compose.onNodeWithText("Anime").assertIsDisplayed()
        compose.onNodeWithText("Moon Reel").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(BROWSED_SOURCE_TAG).assert(hasText("Moon Reel", substring = true))

        compose.onNodeWithTag(seriesViewTag(SeriesView.FOLLOWING)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Nothing followed").assertIsDisplayed()
    }

    @Test
    fun seriesCardKeepsTitleInsideCoverBoundsAndShowsFollowingBadge() {
        val series = Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/one"), "Twin Reincarnation")
        compose.setContent {
            MaterialTheme {
                SeriesCard(series, onClick = {}, followed = true)
            }
        }
        compose.waitForIdle()
        val cardBounds = compose.onNodeWithText("Twin Reincarnation").fetchSemanticsNode().boundsInRoot
        assertTrue(cardBounds.width > 0f && cardBounds.height > 0f)
        compose.onNodeWithText("Following").assertIsDisplayed()
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
    override suspend fun entries(series: Series, stored: List<SeriesEntry>) = SeriesEntries(emptyList())
    override suspend fun media(entry: SeriesEntry) = EntryMedia.Pages(emptyList())

    private fun page(prefix: String, page: Int): SeriesPage = SeriesPage(
        series = (1..count).map { index ->
            Series(SeriesId(key, "/$prefix-$page-$index"), "$prefix $index")
        },
        hasNextPage = false,
    )
}

private val EmptySeriesRepository = StubSeriesRepository()

private val NoMissingActions = MissingExtensionsActions({ error("unused") }, { error("unused") }, onOpenRepos = {})

private class StubSeriesRepository(followed: List<FollowedSeries> = emptyList()) : SeriesRepository {
    private val followed = MutableStateFlow(followed)
    override fun observeFollowed(): Flow<List<FollowedSeries>> = followed
    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = emptyFlow()
    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = emptyFlow()
    override suspend fun series(id: SeriesId): StoredSeries? = null
    override suspend fun entries(id: SeriesId): List<StoredSeriesEntry> = emptyList()
    override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>, seriesMemo: String?) = emptyList<SeriesEntry>()
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) {
        if (!followed) this.followed.update { all -> all.filterNot { it.series.id == id } }
    }
    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
    override suspend fun setChaptersShareFirstPage(id: SeriesId) = Unit
    override suspend fun setAllRead(id: SeriesId, read: Boolean) = EntryProgressRestore()
    override suspend fun setEntriesRead(id: SeriesId, entryAddresses: List<String>, read: Boolean) = EntryProgressRestore()
    override suspend fun restoreProgress(id: SeriesId, restore: EntryProgressRestore) = Unit
    override suspend fun recordProgress(id: SeriesId, entryAddress: String, position: Long, length: Long?, pageOffset: Float?) = Unit
    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
}
