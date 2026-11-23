package com.theoriacodex.extensionhost

import com.theoriacodex.domain.series.Inclusion
import com.theoriacodex.domain.series.SeriesFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AniyomiFiltersTest {
    private val source = FilteringSource()
    private val series = AniyomiSeriesSource(source, HostServices(ExtensionImages()))

    @Test
    fun `each of Aniyomi's filters becomes the app's, and a search sets the values chosen onto the source's own`() = runTest {
        val defaults = listOf(
            SeriesFilter.Header("Options"),
            SeriesFilter.Separator,
            SeriesFilter.Text("Studio", ""),
            SeriesFilter.Check("Dubbed", false),
            SeriesFilter.Select("Status", listOf("Any", "Airing", "Finished"), 0),
            SeriesFilter.Sort("Order", listOf("Title", "Aired"), SeriesFilter.Sort.Selection(1, ascending = false)),
            SeriesFilter.Group("Genres", listOf(SeriesFilter.TriState("Action", Inclusion.IGNORE), SeriesFilter.TriState("Drama", Inclusion.EXCLUDE))),
        )
        assertEquals(defaults, series.filters())

        val chosen = listOf(
            SeriesFilter.Header("Options"),
            SeriesFilter.Separator,
            SeriesFilter.Text("Studio", "Made Up"),
            SeriesFilter.Check("Dubbed", true),
            SeriesFilter.Select("Status", listOf("Any", "Airing", "Finished"), 2),
            SeriesFilter.Sort("Order", listOf("Title", "Aired"), null),
            SeriesFilter.Group("Genres", listOf(SeriesFilter.TriState("Action", Inclusion.INCLUDE), SeriesFilter.TriState("Drama", Inclusion.IGNORE))),
        )
        series.search("one", 1, chosen)

        val searched = checkNotNull(source.searchedWith)
        assertEquals(chosen, searched.nodes().toSeriesFilters())
        assertEquals(AnimeFilter.TriState.STATE_INCLUDE, (searched[6] as Genres).state.first().state)
    }

    private class Studio : AnimeFilter.Text("Studio")

    private class Dubbed : AnimeFilter.CheckBox("Dubbed")

    private class Status : AnimeFilter.Select<String>("Status", arrayOf("Any", "Airing", "Finished"))

    private class Order : AnimeFilter.Sort("Order", arrayOf("Title", "Aired"), Selection(1, ascending = false))

    private class Genre(name: String, state: Int = STATE_IGNORE) : AnimeFilter.TriState(name, state)

    private class Genres : AnimeFilter.Group<Genre>("Genres", listOf(Genre("Action"), Genre("Drama", AnimeFilter.TriState.STATE_EXCLUDE)))

    private class FilteringSource : AnimeHttpSource() {
        override val name = "Fake"
        override val lang = "en"
        override val baseUrl = "https://anime.example"
        override val supportsLatest = false
        var searchedWith: AnimeFilterList? = null

        override fun getFilterList() = AnimeFilterList(AnimeFilter.Header("Options"), AnimeFilter.Separator(), Studio(), Dubbed(), Status(), Order(), Genres())

        override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
            searchedWith = filters
            return AnimesPage(emptyList(), hasNextPage = false)
        }
    }
}
