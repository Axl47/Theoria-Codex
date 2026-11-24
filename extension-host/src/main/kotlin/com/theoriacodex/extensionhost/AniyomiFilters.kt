package com.theoriacodex.extensionhost

import com.theoriacodex.domain.series.SeriesFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter

internal fun List<AnimeFilter<*>>.nodes(): List<FilterNode> = map { it.node() }

private fun AnimeFilter<*>.node(): FilterNode = when (this) {
    is AnimeFilter.Header -> FilterNode(SeriesFilter.Header(name))
    is AnimeFilter.Separator -> FilterNode(SeriesFilter.Separator)
    is AnimeFilter.Text -> filterNode(SeriesFilter.Text(name, state)) { state = it.value }
    is AnimeFilter.CheckBox -> filterNode(SeriesFilter.Check(name, state)) { state = it.checked }
    is AnimeFilter.TriState -> filterNode(SeriesFilter.TriState(name, inclusion(state))) { state = triState(it.state) }
    is AnimeFilter.Select<*> -> filterNode(SeriesFilter.Select(name, values.map { it.toString() }, state)) { state = it.selected }
    is AnimeFilter.Sort -> filterNode(SeriesFilter.Sort(name, values.toList(), state?.let { SeriesFilter.Sort.Selection(it.index, it.ascending) })) {
        state = it.selection?.let { chosen -> AnimeFilter.Sort.Selection(chosen.index, chosen.ascending) }
    }
    is AnimeFilter.Group<*> -> groupNode(name, state.filterIsInstance<AnimeFilter<*>>().nodes())
}
