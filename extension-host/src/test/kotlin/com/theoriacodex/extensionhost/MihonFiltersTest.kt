package com.theoriacodex.extensionhost

import com.theoriacodex.domain.series.Inclusion
import com.theoriacodex.domain.series.SeriesFilter
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class MihonFiltersTest {
    private val source = FilteringSource()
    private val series = MihonSeriesSource(source, ExtensionImages())

    @Test
    fun `each of Mihon's filters becomes the app's, and a group's members that are not filters are left out`() = runTest {
        val expected = listOf(
            SeriesFilter.Header("Options"),
            SeriesFilter.Separator,
            SeriesFilter.Text("Author", ""),
            SeriesFilter.Check("Completed", false),
            SeriesFilter.Select("Status", listOf("Any", "Ongoing", "Status(Ended)"), 0),
            SeriesFilter.Sort("Order", listOf("Title", "Updated"), SeriesFilter.Sort.Selection(1, ascending = false)),
            SeriesFilter.Group(
                "Tags",
                listOf(
                    SeriesFilter.TriState("Action", Inclusion.IGNORE),
                    SeriesFilter.Group("More", listOf(SeriesFilter.TriState("Drama", Inclusion.EXCLUDE))),
                ),
            ),
        )

        assertEquals(expected, series.filters())
    }

    @Test
    fun `a search sets the values chosen onto a list the source makes afresh`() = runTest {
        val chosen = series.filters().map { filter ->
            when (filter) {
                is SeriesFilter.Text -> filter.copy(value = "Oda")
                is SeriesFilter.Check -> filter.copy(checked = true)
                is SeriesFilter.Select -> filter.copy(selected = 2)
                is SeriesFilter.Sort -> filter.copy(selection = null)
                is SeriesFilter.Group -> filter.copy(
                    filters = listOf(
                        SeriesFilter.TriState("Action", Inclusion.INCLUDE),
                        SeriesFilter.Group("More", listOf(SeriesFilter.TriState("Drama", Inclusion.IGNORE))),
                    ),
                )
                else -> filter
            }
        }

        series.search("one", 1, chosen)

        val searched = source.searchedWith.single()
        assertNotSame(source.made.first(), searched)
        assertEquals(chosen, searched.toSeriesFilters())
    }

    @Test
    fun `values that do not line up with the source's list leave its defaults`() = runTest {
        val defaults = series.filters()
        // A list from before the source filled its filters in: the header's place now holds a select of other options.
        val stale = listOf(SeriesFilter.Select("Options", listOf("Old"), 0), SeriesFilter.Separator, SeriesFilter.Check("Author", true))

        series.search("one", 1, stale)

        assertEquals(defaults, source.searchedWith.single().toSeriesFilters())
    }

    @Test
    fun `later pages of a search reuse the list its first page used, and a new search makes another`() = runTest {
        val chosen = series.filters().map { if (it is SeriesFilter.Check) it.copy(checked = true) else it }

        series.search("one", 1, chosen)
        series.search("one", 2, chosen)
        series.search("one", 1, chosen)
        series.search("two", 2, chosen)

        val (first, second, again, other) = source.searchedWith
        assertSame(first, second)
        assertNotSame(first, again)
        assertNotSame(again, other)
    }

    @Test
    fun `no filters search with the source's own list untouched`() = runTest {
        series.search("one", 1)

        assertEquals(series.filters(), source.searchedWith.single().toSeriesFilters())
    }

    private class Status(val label: String) {
        override fun toString() = "Status($label)"
    }

    private class FilteringSource : CatalogueSource {
        override val id = 7L
        override val name = "Fake"
        override val lang = "en"
        override val supportsLatest = true
        val made = mutableListOf<FilterList>()
        val searchedWith = mutableListOf<FilterList>()

        override fun getFilterList() = FilterList(
            object : Filter.Header("Options") {},
            object : Filter.Separator() {},
            object : Filter.Text("Author") {},
            object : Filter.CheckBox("Completed") {},
            object : Filter.Select<Any>("Status", arrayOf("Any", "Ongoing", Status("Ended"))) {},
            object : Filter.Sort("Order", arrayOf("Title", "Updated"), Filter.Sort.Selection(1, ascending = false)) {},
            object : Filter.Group<Any>(
                "Tags",
                listOf(
                    object : Filter.TriState("Action") {},
                    "not a filter",
                    object : Filter.Group<Filter.TriState>("More", listOf(object : Filter.TriState("Drama", Filter.TriState.STATE_EXCLUDE) {})) {},
                ),
            ) {},
        ).also { made += it }

        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), hasNextPage = false)

        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), hasNextPage = false)

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            searchedWith += filters
            return MangasPage(emptyList(), hasNextPage = false)
        }

        override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean) =
            SMangaUpdate(manga, emptyList())

        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }
}
