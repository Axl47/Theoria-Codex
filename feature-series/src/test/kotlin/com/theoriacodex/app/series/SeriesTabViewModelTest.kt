package com.theoriacodex.app.series

import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.SavedStateHandle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesTabViewModelTest {
    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun aSearchSentWhileExtensionsLoadRunsOnceTheyHave() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sources = CompletableDeferred<List<SeriesSource>>()
        val viewModel = SeriesTabViewModel(NothingStored, { sources.await() }) {}

        viewModel.search("one punch")
        advanceUntilIdle()
        assertEquals(emptyList<SeriesSourceListing>(), viewModel.state.value.search)

        sources.complete(listOf(Answering()))
        advanceUntilIdle()
        val listing = viewModel.state.value.search?.single()?.listing as SeriesListing.Loaded
        assertEquals(listOf("one punch"), listing.series.map { it.title })
    }

    @Test
    fun searchGroupsOfSourcesSharingANameNameTheirLanguage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = SeriesTabViewModel(NothingStored, { listOf(Answering(), Answering("custom_mihon_8", language = "fr")) }) {}
        advanceUntilIdle()

        viewModel.search("one punch")
        advanceUntilIdle()

        assertEquals(listOf("Fake · ${languageName("en")}", "Fake · ${languageName("fr")}"), viewModel.state.value.search.orEmpty().map { it.name })
    }

    @Test
    fun aSearchOnScreenRunsInASourceTurnedOnMeanwhile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var offered = listOf<SeriesSource>(Answering())
        val changed = MutableStateFlow(0)
        val viewModel = SeriesTabViewModel(NothingStored, { offered }, changed) {}
        advanceUntilIdle()
        viewModel.search("one punch")
        advanceUntilIdle()

        offered = offered + Answering("custom_mihon_8")
        changed.value = 1
        advanceUntilIdle()

        val found = viewModel.state.value.search.orEmpty().map { (it.listing as SeriesListing.Loaded).series.single().id.source.id }
        assertEquals(listOf("custom_mihon_7", "custom_mihon_8"), found)
    }

    @Test
    fun theSearchAndBrowsedSourceShowAgainAfterProcessDeath() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val offered = listOf<SeriesSource>(Answering(), Answering("custom_mihon_8"))
        val saved = SavedStateHandle()
        val before = SeriesTabViewModel(NothingStored, { offered }, saved = saved) {}
        advanceUntilIdle()
        before.browse(offered[1].key)
        before.search("one punch")
        advanceUntilIdle()

        // The system keeps only the handle's contents; the sources load again in the new process.
        val after = SeriesTabViewModel(NothingStored, { offered }, saved = SavedStateHandle(saved.keys().associateWith { saved.get<String>(it) })) {}
        assertEquals(emptyList<SeriesSourceListing>(), after.state.value.search)
        advanceUntilIdle()

        assertEquals(offered[1].key, after.state.value.browse?.key)
        val found = after.state.value.search.orEmpty().map { (it.listing as SeriesListing.Loaded).series.single().title }
        assertEquals(listOf("one punch", "one punch"), found)
    }

    @Test
    fun moreAddsASourcesNextPageOfResults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Answering(pages = 2)
        val viewModel = SeriesTabViewModel(NothingStored, { listOf(source) }) {}
        advanceUntilIdle()
        viewModel.search("one punch")
        advanceUntilIdle()

        viewModel.searchMore(source.key)
        advanceUntilIdle()

        val listing = viewModel.state.value.search?.single()?.listing as SeriesListing.Loaded
        assertEquals(listOf("/found", "/found2"), listing.series.map { it.id.address })
        assertEquals(null, listing.nextPage)
    }

    @Test
    fun theSameSearchSubmittedAgainDropsAPageStillLoadingForTheFirst() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Answering(pages = 2)
        val viewModel = SeriesTabViewModel(NothingStored, { listOf(source) }) {}
        advanceUntilIdle()
        viewModel.search("one punch")
        advanceUntilIdle()
        val more = CompletableDeferred<Unit>()
        source.laterPagesWait = more
        viewModel.searchMore(source.key)
        advanceUntilIdle()

        viewModel.search("one punch")
        advanceUntilIdle()
        more.complete(Unit)
        advanceUntilIdle()

        val listing = viewModel.state.value.search?.single()?.listing as SeriesListing.Loaded
        assertEquals(listOf("/found"), listing.series.map { it.id.address })
        assertEquals(2, listing.nextPage)
    }

    @Test
    fun aFailedSourceSaysSoAndRetriesTheSameSearch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Answering().apply { failure = SourceAdapterException(SourceFailureReason.NETWORK) }
        val viewModel = SeriesTabViewModel(NothingStored, { listOf(source) }) {}
        advanceUntilIdle()
        viewModel.search("one punch")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.search?.single()?.listing is SeriesListing.Failed)

        source.failure = null
        viewModel.retrySearch(source.key)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.search?.single()?.listing is SeriesListing.Loaded)
    }

    @Test
    fun theFilteredListingPagesLikePopularAndCountsWhatChanged() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Answering(pages = 2)
        val defaults = listOf(SeriesFilter.Check("Completed", false), SeriesFilter.Check("Colored", false))
        source.offered = { defaults }
        val viewModel = SeriesTabViewModel(NothingStored, { listOf(source) }) {}
        advanceUntilIdle()
        viewModel.openFilters()
        advanceUntilIdle()
        assertEquals(defaults, viewModel.state.value.filterSheet?.draft)

        val chosen = listOf(SeriesFilter.Check("Completed", true), SeriesFilter.Check("Colored", false))
        viewModel.editFilters { it.copy(text = " one punch ", draft = chosen) }
        viewModel.searchSource()
        advanceUntilIdle()
        viewModel.browseMore()
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.filterSheet)
        assertEquals(BrowseKind.Filtered("one punch", chosen, changed = 1), viewModel.state.value.browseKind)
        assertEquals(listOf("one punch", "one punch"), browsed(viewModel))
        assertEquals(listOf(chosen, chosen), source.searchedWith)

        // Opened again, the sheet starts at what the listing on screen chose.
        viewModel.openFilters()
        advanceUntilIdle()
        assertEquals(FilterSheetState("one punch", defaults, chosen), viewModel.state.value.filterSheet)

        viewModel.closeFilters()
        viewModel.choose(BrowseKind.Latest)
        advanceUntilIdle()
        assertEquals(listOf("latest"), browsed(viewModel))
    }

    @Test
    fun searchSentBeforeTheFiltersLoadKeepsTheFiltersOnScreen() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val defaults = listOf(SeriesFilter.Check("Completed", false))
        val chosen = listOf(SeriesFilter.Check("Completed", true))
        val slow = CompletableDeferred<List<SeriesFilter>>()
        val answers = ArrayDeque(listOf(CompletableDeferred(defaults), slow))
        val source = Answering().apply { offered = { answers.removeFirst().await() } }
        val viewModel = SeriesTabViewModel(NothingStored, { listOf(source) }) {}
        advanceUntilIdle()
        viewModel.openFilters()
        advanceUntilIdle()
        viewModel.editFilters { it.copy(text = "one", draft = chosen) }
        viewModel.searchSource()
        advanceUntilIdle()

        viewModel.openFilters()
        advanceUntilIdle()
        viewModel.editFilters { it.copy(text = "two") }
        viewModel.searchSource()
        advanceUntilIdle()

        assertEquals(BrowseKind.Filtered("two", chosen, changed = 1), viewModel.state.value.browseKind)
        assertEquals(chosen, source.searchedWith.last())
    }

    @Test
    fun filtersLoadedForASheetClosedMeanwhileNeverReachTheOneOpenNow() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val slow = CompletableDeferred<List<SeriesFilter>>()
        val answers = ArrayDeque(listOf(slow, CompletableDeferred(listOf(SeriesFilter.Check("Completed", false)))))
        val source = Answering().apply { offered = { answers.removeFirst().await() } }
        val viewModel = SeriesTabViewModel(NothingStored, { listOf(source) }) {}
        advanceUntilIdle()
        viewModel.openFilters()
        advanceUntilIdle()
        viewModel.closeFilters()
        viewModel.openFilters()
        advanceUntilIdle()
        val edited = listOf(SeriesFilter.Check("Completed", true))
        viewModel.editFilters { it.copy(draft = edited) }

        slow.complete(listOf(SeriesFilter.Header("Stale")))
        advanceUntilIdle()

        assertEquals(edited, viewModel.state.value.filterSheet?.draft)
    }

    @Test
    fun aPageAskedForUnderAnEarlierListingIsDropped() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Answering(pages = 2)
        val viewModel = SeriesTabViewModel(NothingStored, { listOf(source) }) {}
        advanceUntilIdle()
        val more = CompletableDeferred<Unit>()
        source.laterPagesWait = more
        viewModel.browseMore()
        advanceUntilIdle()

        viewModel.choose(BrowseKind.Latest)
        advanceUntilIdle()
        more.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("latest"), browsed(viewModel))
    }

    @Test
    fun aFailedBrowsedListingIsRetriedAsTheSameKindAndPage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Answering(pages = 2)
        val viewModel = SeriesTabViewModel(NothingStored, { listOf(source) }) {}
        advanceUntilIdle()
        source.failure = SourceAdapterException(SourceFailureReason.NETWORK)
        viewModel.choose(BrowseKind.Latest)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.browse?.listing is SeriesListing.Failed)

        source.failure = null
        viewModel.retryBrowse()
        advanceUntilIdle()
        assertEquals(listOf("latest"), browsed(viewModel))

        source.failure = SourceAdapterException(SourceFailureReason.NETWORK)
        viewModel.browseMore()
        advanceUntilIdle()
        assertEquals(true, (viewModel.state.value.browse?.listing as SeriesListing.Loaded).moreFailed)

        source.failure = null
        viewModel.browseMore()
        advanceUntilIdle()
        val listing = viewModel.state.value.browse?.listing as SeriesListing.Loaded
        assertEquals(listOf("latest", "latest"), browsed(viewModel))
        assertEquals(false, listing.moreFailed)
    }

    @Test
    fun switchingSourceOrLeavingTheTabDropsTheFilters() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val offered = listOf(Answering(), Answering("custom_mihon_8"))
        val viewModel = SeriesTabViewModel(NothingStored, { offered }) {}
        val shown = MutableStateFlow(true)
        viewModel.followShown(shown)
        advanceUntilIdle()
        searchFor(viewModel, "one punch")

        viewModel.openFilters()
        shown.value = false
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.filterSheet)
        assertEquals(BrowseKind.Popular, viewModel.state.value.browseKind)
        assertEquals(listOf("popular"), browsed(viewModel))

        searchFor(viewModel, "one punch")
        viewModel.openFilters()
        viewModel.browse(offered[1].key)
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.filterSheet)
        assertEquals(BrowseKind.Popular, viewModel.state.value.browseKind)
        assertEquals(listOf("popular"), browsed(viewModel))
    }

    @Test
    fun aBrowsedSourceReplacedByAnUpdateIsBrowsedAfresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var offered = listOf(Answering())
        val changed = MutableStateFlow(0)
        val viewModel = SeriesTabViewModel(NothingStored, { offered }, changed) {}
        advanceUntilIdle()
        viewModel.choose(BrowseKind.Latest)
        advanceUntilIdle()

        offered = listOf(Answering())
        changed.value = 1
        advanceUntilIdle()

        assertEquals(BrowseKind.Popular, viewModel.state.value.browseKind)
        assertEquals(listOf("popular"), browsed(viewModel))
    }

    @Test
    fun filtersThatFailToLoadOfferNone() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Answering().apply { offered = { error("no filters") } }
        val viewModel = SeriesTabViewModel(NothingStored, { listOf(source) }) {}
        advanceUntilIdle()

        viewModel.openFilters()
        advanceUntilIdle()

        assertEquals(emptyList<SeriesFilter>(), viewModel.state.value.filterSheet?.draft)
    }

    @Test
    fun theKindFilterShowsOnlyWithBothKindsAndNarrowsTheShelfSourcesAndSearch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manga = Answering("custom_mihon_7")
        val anime = Answering("custom_aniyomi_7")
        val followed = MutableStateFlow(listOf(FollowedSeries(Series(SeriesId(manga.key, "/m"), "M"), unreadCount = 0)))
        var offered = listOf<SeriesSource>(manga)
        val changed = MutableStateFlow(0)
        val viewModel = SeriesTabViewModel(Following(followed), { offered }, changed) {}
        val collecting = launch { viewModel.content.collect {} }
        advanceUntilIdle()
        assertFalse(viewModel.content.value.offersFilter)

        followed.value += FollowedSeries(Series(SeriesId(anime.key, "/a"), "A"), unreadCount = 0)
        offered = listOf(manga, anime)
        changed.value = 1
        viewModel.search("one punch")
        advanceUntilIdle()
        assertTrue("Both kinds show together, marked apart", viewModel.content.value.offersFilter && viewModel.content.value.marked)

        viewModel.chooseKind(SeriesKind.ANIME)
        advanceUntilIdle()
        val content = viewModel.content.value
        assertFalse(content.marked)
        assertEquals(listOf(anime.key), content.followed.map { it.series.id.source })
        assertEquals(listOf(anime.key), content.sources?.map { it.key })
        assertEquals("The browsed manga source gives way", anime.key, content.browse?.key)
        assertEquals(listOf(anime.key), content.search?.map { it.key })
        collecting.cancel()
    }

    @Test
    fun aSourceOfTheNarrowedKindThatComesOnIsBrowsed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manga = Answering("custom_mihon_7")
        val anime = Answering("custom_aniyomi_7")
        // A followed anime whose extension is off: both kinds exist, with only a manga source to browse.
        val followed = MutableStateFlow(
            listOf(
                FollowedSeries(Series(SeriesId(manga.key, "/m"), "M"), unreadCount = 0),
                FollowedSeries(Series(SeriesId(anime.key, "/a"), "A"), unreadCount = 0),
            ),
        )
        var offered = listOf<SeriesSource>(manga)
        val changed = MutableStateFlow(0)
        val viewModel = SeriesTabViewModel(Following(followed), { offered }, changed) {}
        val collecting = launch { viewModel.content.collect {} }
        advanceUntilIdle()
        viewModel.chooseKind(SeriesKind.ANIME)
        advanceUntilIdle()
        assertNull("No anime source is on yet", viewModel.content.value.browse)

        offered = listOf(manga, anime)
        changed.value = 1
        advanceUntilIdle()

        assertEquals(anime.key, viewModel.content.value.browse?.key)
        collecting.cancel()
    }

    private fun TestScope.searchFor(viewModel: SeriesTabViewModel, text: String) {
        viewModel.openFilters()
        advanceUntilIdle()
        viewModel.editFilters { it.copy(text = text) }
        viewModel.searchSource()
        advanceUntilIdle()
    }

    private fun browsed(viewModel: SeriesTabViewModel) = (viewModel.state.value.browse?.listing as SeriesListing.Loaded).series.map { it.title }
}

/**
 * Answers each listing with one series a page over [pages] pages, titled `popular`, `latest` or by the text searched
 * for, and keeps the filters each search was given in [searchedWith].
 */
private class Answering(id: String = "custom_mihon_7", private val pages: Int = 1, override val language: String = "en") : SeriesSource {
    override val key = SourceKey.parseOrThrow(id)
    override val name = "Fake"
    override val supportsLatest = true
    override suspend fun popular(page: Int) = listed("popular", page)
    override suspend fun latest(page: Int) = listed("latest", page)

    var offered: suspend () -> List<SeriesFilter> = { emptyList() }
    override suspend fun filters() = offered()

    val searchedWith = mutableListOf<List<SeriesFilter>>()

    /** What a page after the first waits for, when set. */
    var laterPagesWait: CompletableDeferred<Unit>? = null

    /** What every listing throws, when set. */
    var failure: Exception? = null
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>): SeriesPage {
        searchedWith += filters
        return listed(text, page)
    }

    private suspend fun listed(title: String, page: Int): SeriesPage {
        failure?.let { throw it }
        if (page > 1) laterPagesWait?.await()
        return SeriesPage(listOf(Series(SeriesId(key, if (page == 1) "/found" else "/found$page"), title)), hasNextPage = page < pages)
    }
    override suspend fun details(series: Series) = series
    override suspend fun entries(series: Series) = emptyList<SeriesEntry>()
    override suspend fun media(entry: SeriesEntry) = EntryMedia.Pages(emptyList())
}

private class Following(private val followed: Flow<List<FollowedSeries>>) : SeriesRepository by NothingStored {
    override fun observeFollowed() = followed
}

private object NothingStored : SeriesRepository {
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
    override suspend fun recordProgress(id: SeriesId, entryAddress: String, position: Long, length: Long?) = Unit
    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
}
