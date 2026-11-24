package com.theoriacodex.app.series

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntries
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.CompletableDeferred

internal class Answering(id: String = "custom_mihon_7", private val pages: Int = 1, override val language: String = "en") : SeriesSource {
    override val key = SourceKey.parseOrThrow(id)
    override val name = "Fake"
    override val supportsLatest = true
    override suspend fun popular(page: Int) = listed("popular", page)
    override suspend fun latest(page: Int) = listed("latest", page)

    var offered: suspend () -> List<SeriesFilter> = { emptyList() }
    override suspend fun filters() = offered()

    val searchedWith = mutableListOf<List<SeriesFilter>>()

    var laterPagesWait: CompletableDeferred<Unit>? = null

    private val holds = mutableMapOf<String, CompletableDeferred<Unit>>()
    fun hold(title: String) = CompletableDeferred<Unit>().also { holds[title] = it }

    val asked = mutableListOf<String>()

    var failure: Exception? = null
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>): SeriesPage {
        searchedWith += filters
        return listed(text, page)
    }

    private suspend fun listed(title: String, page: Int): SeriesPage {
        asked += title
        failure?.let { throw it }
        if (page == 1) holds[title]?.await()
        if (page > 1) laterPagesWait?.await()
        return SeriesPage(listOf(Series(SeriesId(key, if (page == 1) "/found" else "/found$page"), title)), hasNextPage = page < pages)
    }
    override suspend fun details(series: Series) = series
    override suspend fun entries(series: Series, stored: List<SeriesEntry>) = SeriesEntries(emptyList())
    override suspend fun media(entry: SeriesEntry) = EntryMedia.Pages(emptyList())
}

internal fun browsed(viewModel: SeriesTabViewModel) = (viewModel.state.value.browse?.listing as SeriesListing.Loaded).series.map { it.title }
