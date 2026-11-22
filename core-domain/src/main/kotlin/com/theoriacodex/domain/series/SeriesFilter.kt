package com.theoriacodex.domain.series

/**
 * One search option a [SeriesSource] offers, with its value. A source's [SeriesSource.filters] lists them at their
 * defaults, in its own order; a search hands back the same list with values changed.
 */
sealed interface SeriesFilter {
    val name: String

    /** A label above the filters that follow; it has no value. */
    data class Header(override val name: String) : SeriesFilter

    data object Separator : SeriesFilter {
        override val name = ""
    }

    data class Text(override val name: String, val value: String) : SeriesFilter

    data class Check(override val name: String, val checked: Boolean) : SeriesFilter

    data class TriState(override val name: String, val state: Inclusion) : SeriesFilter

    /** One of [options]; [selected] is its index. */
    data class Select(override val name: String, val options: List<String>, val selected: Int) : SeriesFilter

    /** An order by one of [options], or the source's own order when [selection] is null. */
    data class Sort(override val name: String, val options: List<String>, val selection: Selection?) : SeriesFilter {
        data class Selection(val index: Int, val ascending: Boolean)
    }

    data class Group(override val name: String, val filters: List<SeriesFilter>) : SeriesFilter
}

/** Whether a [SeriesFilter.TriState] ignores its option, requires it, or rules it out. */
enum class Inclusion { IGNORE, INCLUDE, EXCLUDE }

/**
 * These filters (a source's, at their defaults) with each value from [chosen] that still lines up: the same place,
 * kind and name and, for an option, one these filters still offer. Anything else keeps its default. A source can
 * offer different filters from one call to the next (some fill theirs in from the network), so choices made against
 * an earlier list never fail a search; at worst they are left out of it.
 */
fun List<SeriesFilter>.withChoices(chosen: List<SeriesFilter>): List<SeriesFilter> =
    mapIndexed { index, filter -> chosen.getOrNull(index)?.let(filter::withChoice) ?: filter }

private fun SeriesFilter.withChoice(chosen: SeriesFilter): SeriesFilter = when {
    chosen.name != name -> this
    this is SeriesFilter.Text && chosen is SeriesFilter.Text -> copy(value = chosen.value)
    this is SeriesFilter.Check && chosen is SeriesFilter.Check -> copy(checked = chosen.checked)
    this is SeriesFilter.TriState && chosen is SeriesFilter.TriState -> copy(state = chosen.state)
    // Options are matched by label, so an option list that changed order still selects what was chosen.
    this is SeriesFilter.Select && chosen is SeriesFilter.Select ->
        options.indexOf(chosen.options.getOrNull(chosen.selected)).takeIf { it >= 0 }?.let { copy(selected = it) } ?: this
    this is SeriesFilter.Sort && chosen is SeriesFilter.Sort -> when (val selection = chosen.selection) {
        null -> copy(selection = null)
        else -> options.indexOf(chosen.options.getOrNull(selection.index)).takeIf { it >= 0 }
            ?.let { copy(selection = SeriesFilter.Sort.Selection(it, selection.ascending)) } ?: this
    }
    this is SeriesFilter.Group && chosen is SeriesFilter.Group -> copy(filters = filters.withChoices(chosen.filters))
    else -> this
}

/** How many of these filters differ from [defaults], the list they were made from; a group counts each changed member. */
fun List<SeriesFilter>.changedFrom(defaults: List<SeriesFilter>): Int = zip(defaults).sumOf { (filter, default) ->
    if (filter is SeriesFilter.Group && default is SeriesFilter.Group) filter.filters.changedFrom(default.filters)
    else if (filter != default) 1 else 0
}
