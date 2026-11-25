package com.theoriacodex.app.series

import com.theoriacodex.data.repository.NoChaptersToMoveToException
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
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

    private fun TestScope.viewModel(
        vararg others: SeriesSource,
        skips: MemorySwitchSourceSkips = MemorySwitchSourceSkips(),
        switch: suspend (SeriesSource, Series) -> Boolean = { _, _ -> true },
    ): SwitchSourceViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return SwitchSourceViewModel(from, { listOf(current) + others }, store = {}, choices = skips, switch = switch)
    }

    @Test
    fun onlyCheckedSourcesAreSearchedAndEachCheckSearchesOrDropsJustThatSource() = runTest {
        val checked = Finding("custom_mihon_2", listOf("Hero"))
        val unchecked = Finding("custom_mihon_3", listOf("Hero"))
        val skips = MemorySwitchSourceSkips(setOf(unchecked.key))
        val viewModel = viewModel(checked, unchecked, skips = skips)
        advanceUntilIdle()

        viewModel.open("Hero")
        advanceUntilIdle()
        assertEquals(listOf(checked.key), viewModel.state.value.sheet!!.results.map { it.key })
        assertEquals(emptyList<String>(), unchecked.searched)

        viewModel.toggle(listOf(unchecked.key))
        advanceUntilIdle()
        assertEquals(listOf(checked.key, unchecked.key), viewModel.state.value.sheet!!.results.map { it.key })
        assertEquals("A check searches only the checked source", listOf("Hero"), checked.searched)
        assertEquals(listOf("Hero"), unchecked.searched)

        viewModel.toggle(listOf(checked.key))
        advanceUntilIdle()
        assertEquals(listOf(unchecked.key), viewModel.state.value.sheet!!.results.map { it.key })
        assertEquals(setOf(checked.key), skips.skipped)
    }

    @Test
    fun theNextSheetStartsFromTheLastChoiceAndASourceAddedSinceStartsChecked() = runTest {
        val kept = Finding("custom_mihon_2", listOf("Hero"))
        val dropped = Finding("custom_mihon_3", listOf("Hero"))
        val skips = MemorySwitchSourceSkips(setOf(SourceKey.parseOrThrow("custom_aniyomi_9")))
        val first = viewModel(kept, dropped, skips = skips)
        advanceUntilIdle()
        first.open("Hero")
        advanceUntilIdle()
        first.toggle(listOf(dropped.key))
        advanceUntilIdle()
        assertEquals("Choices for sources this sheet does not offer are kept", setOf(SourceKey.parseOrThrow("custom_aniyomi_9"), dropped.key), skips.skipped)

        val added = Finding("custom_mihon_4", listOf("Hero"))
        val next = viewModel(kept, dropped, added, skips = skips)
        advanceUntilIdle()
        next.open("Hero")
        advanceUntilIdle()

        assertEquals(listOf(kept.key, added.key), next.state.value.sheet!!.results.map { it.key })
        assertEquals(listOf("Hero"), dropped.searched)
    }

    @Test
    fun twoOpenSheetsKeepEachOthersChoices() = runTest {
        val a = Finding("custom_mihon_2")
        val b = Finding("custom_mihon_3")
        val skips = MemorySwitchSourceSkips()
        val first = viewModel(a, b, skips = skips)
        val second = viewModel(a, b, skips = skips)
        advanceUntilIdle()
        first.open("Hero")
        second.open("Hero")
        advanceUntilIdle()

        first.toggle(listOf(a.key))
        advanceUntilIdle()
        second.toggle(listOf(b.key))
        advanceUntilIdle()

        assertEquals(setOf(a.key, b.key), skips.skipped)
    }

    @Test
    fun uncheckingASourceCancelsItsSearchAndNothingCheckedSearchesNothing() = runTest {
        val slow = Finding("custom_mihon_2", listOf("Hero")).apply { wait = CompletableDeferred() }
        val viewModel = viewModel(slow)
        advanceUntilIdle()
        viewModel.open("Hero")
        advanceUntilIdle()

        viewModel.toggle(listOf(slow.key))
        advanceUntilIdle()
        assertTrue(slow.cancelled)
        assertEquals(emptyList<SwitchSourceResult>(), viewModel.state.value.sheet!!.results)

        viewModel.searchAgain()
        viewModel.retry(slow.key)
        advanceUntilIdle()
        assertEquals(listOf("Hero"), slow.searched)
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
        assertEquals(listOf(12, 12, 12), found.matches.map { it.entries })
        assertEquals("Only the rows shown are counted", 3, titled.counted)
        assertEquals(SwitchListing.Found(emptyList()), results[1].listing)
    }

    @Test
    fun anEditedQuerySearchesEverySourceAgainAndRetriesUseIt() = runTest {
        val failing = Finding("custom_mihon_2", listOf("Yuusha")).apply { fails = true }
        val other = Finding("custom_mihon_3", listOf("Yuusha"))
        val viewModel = viewModel(failing, other)
        advanceUntilIdle()
        viewModel.open("The Hero")
        advanceUntilIdle()
        assertEquals("The Hero", viewModel.state.value.sheet!!.query)

        viewModel.editQuery("  Yuusha ")
        viewModel.searchAgain()
        advanceUntilIdle()
        failing.fails = false
        viewModel.retry(failing.key)
        advanceUntilIdle()

        assertEquals(listOf("The Hero", "Yuusha"), other.searched)
        assertEquals(listOf("The Hero", "Yuusha", "Yuusha"), failing.searched)
        viewModel.editQuery(" ")
        viewModel.searchAgain()
        advanceUntilIdle()
        assertEquals("A blank query searches nothing", 2, other.searched.size)
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
    fun aSeriesWhoseOwnSourceIsNotLoadedStillSwitchesToALoadedOne() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val other = Finding("custom_mihon_2", listOf("Hero"))
        var moved: Pair<SourceKey, SeriesId>? = null
        val viewModel = SwitchSourceViewModel(from, { listOf(other) }, store = {}, choices = MemorySwitchSourceSkips()) { source, to -> moved = source.key to to.id; true }
        advanceUntilIdle()
        viewModel.open("Hero")
        advanceUntilIdle()
        val match = (viewModel.state.value.sheet!!.results.single().listing as SwitchListing.Found).matches.single()

        viewModel.switchTo(match) {}
        advanceUntilIdle()

        assertEquals(other.key to match.series.id, moved)
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
        assertEquals(SwitchSourceSheet(viewModel.state.value.sheet!!.results, query = "Hero", switching = true), viewModel.state.value.sheet)

        outcome.complete(false)
        advanceUntilIdle()
        assertNull(switched)
        assertNull(viewModel.state.value.sheet)
    }

    @Test
    fun aSeriesWithoutChaptersSaysSoAndSourcesOfOneNameAreToldApartByLanguage() = runTest {
        val french = Finding("custom_mihon_2", listOf("Hero"), name = "Paper Lantern", language = "fr")
        val viewModel = viewModel(french, Finding("custom_mihon_3", name = "Paper Lantern")) { _, _ -> throw NoChaptersToMoveToException() }
        advanceUntilIdle()
        viewModel.open("Hero")
        advanceUntilIdle()
        val result = viewModel.state.value.sheet!!.results.first()
        assertEquals("Paper Lantern · ${languageName("fr")}", result.name)

        viewModel.switchTo((result.listing as SwitchListing.Found).matches.single()) {}
        advanceUntilIdle()
        assertEquals("No chapters to move your progress to", viewModel.state.value.sheet!!.failure)
    }

    @Test
    fun anAnimeSwitchesOnlyBetweenAnimeSourcesAndSaysEpisodes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val own = Finding("custom_aniyomi_1")
        val anime = Finding("custom_aniyomi_2", listOf("Hero"))
        val viewModel = SwitchSourceViewModel(SeriesId(own.key, "/mine"), { listOf(own, Finding("custom_mihon_2", listOf("Hero")), anime) }, store = {}, choices = MemorySwitchSourceSkips()) { _, _ ->
            throw NoChaptersToMoveToException()
        }
        advanceUntilIdle()

        assertEquals(listOf(anime.key), viewModel.state.value.others.map { it.key })
        viewModel.open("Hero")
        advanceUntilIdle()
        viewModel.switchTo((viewModel.state.value.sheet!!.results.single().listing as SwitchListing.Found).matches.single()) {}
        advanceUntilIdle()
        assertEquals("No episodes to move your progress to", viewModel.state.value.sheet!!.failure)
    }
}

private class Finding(
    id: String,
    private val titles: List<String> = emptyList(),
    override val name: String = id,
    override val language: String = "en",
) : SeriesSource {
    override val key = SourceKey.parseOrThrow(id)
    var chapters = 1
    var fails = false

    var failure: Exception? = null
    var counted = 0
    val searched = mutableListOf<String>()

    var wait: CompletableDeferred<Unit>? = null
    var cancelled = false

    override suspend fun popular(page: Int) = SeriesPage(emptyList(), hasNextPage = false)
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>): SeriesPage {
        searched += text
        try {
            wait?.await()
        } catch (error: CancellationException) {
            cancelled = true
            throw error
        }
        check(!fails)
        failure?.let { throw it }
        return SeriesPage(titles.mapIndexed { index, title -> Series(SeriesId(key, "/$index"), title) }, hasNextPage = false)
    }
    override suspend fun details(series: Series) = series
    override suspend fun entries(series: Series, stored: List<SeriesEntry>): SeriesEntries {
        counted++
        return SeriesEntries(List(chapters) { SeriesEntry("/c$it", "Chapter $it") })
    }
    override suspend fun media(entry: SeriesEntry) = EntryMedia.Pages(emptyList())
}
