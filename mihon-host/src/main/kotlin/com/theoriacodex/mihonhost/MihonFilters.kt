package com.theoriacodex.mihonhost

import com.theoriacodex.domain.series.Inclusion
import com.theoriacodex.domain.series.SeriesFilter
import eu.kanade.tachiyomi.source.model.Filter

/*
 * Mihon's filters are objects the source makes afresh for each search and reads back from; extensions subclass them,
 * so the host only reads and sets their `state`. The app's model mirrors the same tree, so values pass by position.
 */

/** A source's filter tree in the app's model. A group's members that are not filters are left out, in both directions. */
internal fun List<Filter<*>>.toSeriesFilters(): List<SeriesFilter> = map { it.toSeriesFilter() }

private fun Filter<*>.toSeriesFilter(): SeriesFilter = when (this) {
    is Filter.Header -> SeriesFilter.Header(name)
    is Filter.Separator -> SeriesFilter.Separator
    is Filter.Text -> SeriesFilter.Text(name, state)
    is Filter.CheckBox -> SeriesFilter.Check(name, state)
    is Filter.TriState -> SeriesFilter.TriState(name, inclusion(state))
    is Filter.Select<*> -> SeriesFilter.Select(name, values.map { it.toString() }, state)
    is Filter.Sort -> SeriesFilter.Sort(name, values.toList(), state?.let { SeriesFilter.Sort.Selection(it.index, it.ascending) })
    is Filter.Group<*> -> SeriesFilter.Group(name, members().toSeriesFilters())
}

/**
 * Sets each filter's state from [values], which must have been made from this same tree ([toSeriesFilters], then
 * [com.theoriacodex.domain.series.withChoices]); a value of another kind is skipped rather than trusted.
 */
internal fun List<Filter<*>>.setFrom(values: List<SeriesFilter>) {
    zip(values).forEach { (filter, value) -> filter.setFrom(value) }
}

private fun Filter<*>.setFrom(value: SeriesFilter) {
    when {
        this is Filter.Text && value is SeriesFilter.Text -> state = value.value
        this is Filter.CheckBox && value is SeriesFilter.Check -> state = value.checked
        this is Filter.TriState && value is SeriesFilter.TriState -> state = triState(value.state)
        this is Filter.Select<*> && value is SeriesFilter.Select -> state = value.selected
        this is Filter.Sort && value is SeriesFilter.Sort -> state = value.selection?.let { Filter.Sort.Selection(it.index, it.ascending) }
        this is Filter.Group<*> && value is SeriesFilter.Group -> members().setFrom(value.filters)
    }
}

private fun Filter.Group<*>.members(): List<Filter<*>> = state.filterIsInstance<Filter<*>>()

private fun inclusion(state: Int): Inclusion = when (state) {
    Filter.TriState.STATE_INCLUDE -> Inclusion.INCLUDE
    Filter.TriState.STATE_EXCLUDE -> Inclusion.EXCLUDE
    else -> Inclusion.IGNORE
}

private fun triState(inclusion: Inclusion): Int = when (inclusion) {
    Inclusion.IGNORE -> Filter.TriState.STATE_IGNORE
    Inclusion.INCLUDE -> Filter.TriState.STATE_INCLUDE
    Inclusion.EXCLUDE -> Filter.TriState.STATE_EXCLUDE
}
