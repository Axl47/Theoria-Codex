package com.theoriacodex.extensionhost

import com.theoriacodex.domain.series.SeriesFilter
import eu.kanade.tachiyomi.source.model.Filter

/** A Mihon source's filter tree as [FilterNode]s (see `ExtensionFilters.kt`). */
internal fun List<Filter<*>>.nodes(): List<FilterNode> = map { it.node() }

private fun Filter<*>.node(): FilterNode = when (this) {
    is Filter.Header -> FilterNode(SeriesFilter.Header(name))
    is Filter.Separator -> FilterNode(SeriesFilter.Separator)
    is Filter.Text -> filterNode(SeriesFilter.Text(name, state)) { state = it.value }
    is Filter.CheckBox -> filterNode(SeriesFilter.Check(name, state)) { state = it.checked }
    is Filter.TriState -> filterNode(SeriesFilter.TriState(name, inclusion(state))) { state = triState(it.state) }
    is Filter.Select<*> -> filterNode(SeriesFilter.Select(name, values.map { it.toString() }, state)) { state = it.selected }
    is Filter.Sort -> filterNode(SeriesFilter.Sort(name, values.toList(), state?.let { SeriesFilter.Sort.Selection(it.index, it.ascending) })) {
        state = it.selection?.let { chosen -> Filter.Sort.Selection(chosen.index, chosen.ascending) }
    }
    is Filter.Group<*> -> groupNode(name, state.filterIsInstance<Filter<*>>().nodes())
}
