package com.theoriacodex.app.series

import com.theoriacodex.data.repository.NoChaptersToMoveToException
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SwitchSourceViewModelTest {
    private val current = Finding("custom_mihon_1")
    private val from = SeriesId(current.key, "/mine")

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(vararg others: SeriesSource, switch: suspend (SeriesSource, Series) -> Boolean = { _, _ -> true }): SwitchSourceViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return SwitchSourceViewModel(from, { listOf(current) + others }, store = {}, switch = switch)
    }

    @Test
    fun eachOtherSourceShowsItsThreeBestMatchesFirstExactThenItsOwnOrder() = runTest {
        val titled = Finding("custom_mihon_2", listOf("The Hero 2", "Hero Again", "the hero!", "Heroes", "Hero")).apply { chapters = 12 }
        val empty = Finding("custom_mihon_3")
        val viewModel = viewModel(titled, empty)
        advanceUntilIdle()

        viewModel.open("The Hero")
        advanceUntilIdle()

        val results = viewModel.state.value.sheet!!.results
        assertEquals("The series' own source is not searched", listOf(titled.key, empty.key), results.map { it.key })
        val found = results[0].listing as SwitchListing.Found
        assertEquals(listOf("the hero!", "The Hero 2", "Hero Again"), found.matches.map { it.series.title })
        assertEquals(listOf(12, 12, 12), found.matches.map { it.chapters })
        assertEquals("Only the rows shown are counted", 3, titled.counted)
        assertEquals(SwitchListing.Found(emptyList()), results[1].listing)
    }

    @Test
    fun aFailedSourceCanBeRetriedWhileTheOthersStand() = runTest {
        val failing = Finding("custom_mihon_2", listOf("Hero")).apply { fails = true }
        val viewModel = viewModel(failing)
        advanceUntilIdle()
        viewModel.open("Hero")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.sheet!!.results.single().listing is SwitchListing.Failed)

        failing.fails = false
        viewModel.retry(failing.key)
        advanceUntilIdle()

        val found = viewModel.state.value.sheet!!.results.single().listing as SwitchListing.Found
        assertEquals(listOf("Hero"), found.matches.map { it.series.title })
    }

    @Test
    fun closingTheSheetCancelsItsSearches() = runTest {
        val slow = Finding("custom_mihon_2", listOf("Hero")).apply { wait = CompletableDeferred() }
        val viewModel = viewModel(slow)
        advanceUntilIdle()
        viewModel.open("Hero")
        advanceUntilIdle()

        viewModel.close()
        advanceUntilIdle()

        assertTrue(slow.cancelled)
        assertNull(viewModel.state.value.sheet)
    }

    @Test
    fun aFailedSwitchStaysOnTheSheetAndASwitchThatWorksClosesIt() = runTest {
        val other = Finding("custom_mihon_2", listOf("Hero"))
        var works = false
        val viewModel = viewModel(other) { _, _ -> check(works); true }
        advanceUntilIdle()
        viewModel.open("Hero")
        advanceUntilIdle()
        val match = (viewModel.state.value.sheet!!.results.single().listing as SwitchListing.Found).matches.single()
        var switched: SeriesId? = null

        viewModel.switchTo(match) { switched = it }
        advanceUntilIdle()
        assertEquals("Request failed for an unknown reason.", viewModel.state.value.sheet!!.failure)

        works = true
        viewModel.switchTo(match) { switched = it }
        advanceUntilIdle()
        assertEquals(match.series.id, switched)
        assertNull(viewModel.state.value.sheet)
    }

    @Test
    fun aSwitchUnderWayHoldsTheSheetAndOneThatMovedNothingOnlyClosesIt() = runTest {
        val other = Finding("custom_mihon_2", listOf("Hero"))
        val outcome = CompletableDeferred<Boolean>()
        val viewModel = viewModel(other) { _, _ -> outcome.await() }
        advanceUntilIdle()
        viewModel.open("Hero")
        advanceUntilIdle()
        val match = (viewModel.state.value.sheet!!.results.single().listing as SwitchListing.Found).matches.single()
        var switched: SeriesId? = null
        viewModel.switchTo(match) { switched = it }
        advanceUntilIdle()

        viewModel.close()
        viewModel.confirm(match)
        assertEquals(SwitchSourceSheet(viewModel.state.value.sheet!!.results, switching = true), viewModel.state.value.sheet)

        outcome.complete(false)
        advanceUntilIdle()
        assertNull(switched)
        assertNull(viewModel.state.value.sheet)
    }

    @Test
    fun aSeriesWithoutChaptersSaysSoAndSourcesOfOneNameAreToldApartByLanguage() = runTest {
        val french = Finding("custom_mihon_2", listOf("Hero"), name = "MangaDex", language = "fr")
        val viewModel = viewModel(french, Finding("custom_mihon_3", name = "MangaDex")) { _, _ -> throw NoChaptersToMoveToException() }
        advanceUntilIdle()
        viewModel.open("Hero")
        advanceUntilIdle()
        val result = viewModel.state.value.sheet!!.results.first()
        assertEquals("MangaDex · ${languageName("fr")}", result.name)

        viewModel.switchTo((result.listing as SwitchListing.Found).matches.single()) {}
        advanceUntilIdle()
        assertEquals(NO_CHAPTERS, viewModel.state.value.sheet!!.failure)
    }
}

/** Finds [titles] for any search, in that order, and lists [chapters] chapters for each. */
private class Finding(
    id: String,
    private val titles: List<String> = emptyList(),
    override val name: String = id,
    override val language: String = "en",
) : SeriesSource {
    override val key = SourceKey.parseOrThrow(id)
    var chapters = 1
    var fails = false
    var counted = 0

    /** What a search waits for, when set; [cancelled] says whether a waiting search was cancelled. */
    var wait: CompletableDeferred<Unit>? = null
    var cancelled = false

    override suspend fun popular(page: Int) = SeriesPage(emptyList(), hasNextPage = false)
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>): SeriesPage {
        try {
            wait?.await()
        } catch (error: CancellationException) {
            cancelled = true
            throw error
        }
        check(!fails)
        return SeriesPage(titles.mapIndexed { index, title -> Series(SeriesId(key, "/$index"), title) }, hasNextPage = false)
    }
    override suspend fun details(series: Series) = series
    override suspend fun entries(series: Series): List<SeriesEntry> {
        counted++
        return List(chapters) { SeriesEntry("/c$it", "Chapter $it") }
    }
    override suspend fun pages(entry: SeriesEntry) = emptyList<ImageRef>()
}
