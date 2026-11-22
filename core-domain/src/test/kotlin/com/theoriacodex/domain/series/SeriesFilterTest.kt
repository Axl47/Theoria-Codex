package com.theoriacodex.domain.series

import com.theoriacodex.domain.series.SeriesFilter.Check
import com.theoriacodex.domain.series.SeriesFilter.Group
import com.theoriacodex.domain.series.SeriesFilter.Header
import com.theoriacodex.domain.series.SeriesFilter.Select
import com.theoriacodex.domain.series.SeriesFilter.Separator
import com.theoriacodex.domain.series.SeriesFilter.Sort
import com.theoriacodex.domain.series.SeriesFilter.Text
import com.theoriacodex.domain.series.SeriesFilter.TriState
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesFilterTest {
    private val defaults = listOf(
        Header("Options"),
        Separator,
        Text("Author", ""),
        Check("Completed", false),
        Select("Status", listOf("Any", "Ongoing", "Ended"), 0),
        Sort("Order", listOf("Title", "Updated"), null),
        Group("Tags", listOf(TriState("Action", Inclusion.IGNORE), Group("More", listOf(Check("Long strip", false))))),
    )

    @Test
    fun `every kind of value is taken, nested groups included`() {
        val chosen = listOf(
            Header("Options"),
            Separator,
            Text("Author", "Oda"),
            Check("Completed", true),
            Select("Status", listOf("Any", "Ongoing", "Ended"), 2),
            Sort("Order", listOf("Title", "Updated"), Sort.Selection(1, ascending = true)),
            Group("Tags", listOf(TriState("Action", Inclusion.EXCLUDE), Group("More", listOf(Check("Long strip", true))))),
        )

        val merged = defaults.withChoices(chosen)

        assertEquals(chosen, merged)
        assertEquals(6, merged.changedFrom(defaults))
    }

    @Test
    fun `a value that no longer lines up keeps the default`() {
        val chosen = listOf(
            Check("Options", true), // another kind in the header's place
            Separator,
            Text("Artist", "Oda"), // another name
            Check("Completed", true),
            Select("Status", listOf("Any", "Hiatus"), 1), // an option the source no longer offers
            Sort("Order", listOf("Title", "Updated"), Sort.Selection(5, ascending = true)), // out of range
        )

        val merged = defaults.withChoices(chosen)

        assertEquals(defaults.toMutableList().also { it[3] = Check("Completed", true) }, merged)
        assertEquals(1, merged.changedFrom(defaults))
    }

    @Test
    fun `options are matched by label when the source reorders them`() {
        val reordered = listOf(Select("Status", listOf("Ended", "Any", "Ongoing"), 1))

        val merged = reordered.withChoices(listOf(Select("Status", listOf("Any", "Ongoing", "Ended"), 1)))

        assertEquals(listOf(Select("Status", listOf("Ended", "Any", "Ongoing"), 2)), merged)
    }

    @Test
    fun `a shorter or longer list on either side keeps what lines up`() {
        val first = listOf(Header("Press reset to load tags"))
        val later = listOf(Header("Press reset to load tags"), Check("Completed", false))

        assertEquals(later, later.withChoices(first))
        assertEquals(first, first.withChoices(later.map { if (it is Check) it.copy(checked = true) else it }))
    }

    @Test
    fun `a sort chosen as the source's own order clears the default order`() {
        val sorted = listOf(Sort("Order", listOf("Title"), Sort.Selection(0, ascending = false)))

        assertEquals(listOf(Sort("Order", listOf("Title"), null)), sorted.withChoices(listOf(Sort("Order", listOf("Title"), null))))
    }
}
