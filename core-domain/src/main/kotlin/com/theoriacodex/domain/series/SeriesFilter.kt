package com.theoriacodex.domain.series

sealed interface SeriesFilter {
    val name: String

    data class Header(override val name: String) : SeriesFilter

    data object Separator : SeriesFilter {
        override val name = ""
    }

    data class Text(override val name: String, val value: String) : SeriesFilter

    data class Check(override val name: String, val checked: Boolean) : SeriesFilter

    data class TriState(override val name: String, val state: Inclusion) : SeriesFilter

    data class Select(override val name: String, val options: List<String>, val selected: Int) : SeriesFilter

    data class Sort(override val name: String, val options: List<String>, val selection: Selection?) : SeriesFilter {
        data class Selection(val index: Int, val ascending: Boolean)
    }

    data class Group(override val name: String, val filters: List<SeriesFilter>) : SeriesFilter
}

enum class Inclusion { IGNORE, INCLUDE, EXCLUDE }

fun List<SeriesFilter>.withChoices(chosen: List<SeriesFilter>): List<SeriesFilter> =
    mapIndexed { index, filter -> chosen.getOrNull(index)?.let(filter::withChoice) ?: filter }

private fun SeriesFilter.withChoice(chosen: SeriesFilter): SeriesFilter = when {
    chosen.name != name -> this
    this is SeriesFilter.Text && chosen is SeriesFilter.Text -> copy(value = chosen.value)
    this is SeriesFilter.Check && chosen is SeriesFilter.Check -> copy(checked = chosen.checked)
    this is SeriesFilter.TriState && chosen is SeriesFilter.TriState -> copy(state = chosen.state)
    this is SeriesFilter.Select && chosen is SeriesFilter.Select ->
        options.placeOf(chosen.options, chosen.selected)?.let { copy(selected = it) } ?: this
    this is SeriesFilter.Sort && chosen is SeriesFilter.Sort -> when (val selection = chosen.selection) {
        null -> copy(selection = null)
        else -> options.placeOf(chosen.options, selection.index)?.let { copy(selection = SeriesFilter.Sort.Selection(it, selection.ascending)) } ?: this
    }
    this is SeriesFilter.Group && chosen is SeriesFilter.Group -> copy(filters = filters.withChoices(chosen.filters))
    else -> this
}

private fun List<String>.placeOf(chosen: List<String>, index: Int): Int? {
    val label = chosen.getOrNull(index) ?: return null
    if (getOrNull(index) == label) return index
    return indexOf(label).takeIf { it >= 0 && it == lastIndexOf(label) }
}

fun List<SeriesFilter>.changedFrom(defaults: List<SeriesFilter>): Int = zip(defaults).sumOf { (filter, default) ->
    if (filter is SeriesFilter.Group && default is SeriesFilter.Group) filter.filters.changedFrom(default.filters)
    else if (filter != default) 1 else 0
}
