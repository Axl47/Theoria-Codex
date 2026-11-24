package com.theoriacodex.extensionhost

import com.theoriacodex.domain.series.Inclusion
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.withChoices
import eu.kanade.tachiyomi.source.model.Filter

internal class FilterNode(val value: SeriesFilter, val set: (SeriesFilter) -> Unit = {})

internal inline fun <reified V : SeriesFilter> filterNode(value: V, crossinline set: (V) -> Unit): FilterNode =
    FilterNode(value) { chosen -> (chosen as? V)?.let { set(it) } }

internal fun groupNode(name: String, members: List<FilterNode>): FilterNode =
    filterNode(SeriesFilter.Group(name, members.toSeriesFilters())) { members.setFrom(it.filters) }

internal fun List<FilterNode>.toSeriesFilters(): List<SeriesFilter> = map(FilterNode::value)

internal fun List<FilterNode>.setFrom(values: List<SeriesFilter>) {
    zip(values).forEach { (node, value) -> node.set(value) }
}

internal fun inclusion(state: Int): Inclusion = when (state) {
    Filter.TriState.STATE_INCLUDE -> Inclusion.INCLUDE
    Filter.TriState.STATE_EXCLUDE -> Inclusion.EXCLUDE
    else -> Inclusion.IGNORE
}

internal fun triState(inclusion: Inclusion): Int = when (inclusion) {
    Inclusion.IGNORE -> Filter.TriState.STATE_IGNORE
    Inclusion.INCLUDE -> Filter.TriState.STATE_INCLUDE
    Inclusion.EXCLUDE -> Filter.TriState.STATE_EXCLUDE
}

internal class FilterSearch<L>(private val make: () -> L, private val nodes: (L) -> List<FilterNode>) {
    private class Paged<L>(val text: String, val filters: List<SeriesFilter>, val options: L)

    @Volatile private var paged: Paged<L>? = null

    fun defaults(): List<SeriesFilter> = nodes(make()).toSeriesFilters()

    fun options(text: String, page: Int, filters: List<SeriesFilter>): L {
        val options = paged?.takeIf { page > 1 && it.text == text && it.filters == filters }?.options
            ?: make().also { options ->
                if (filters.isNotEmpty()) nodes(options).let { tree -> tree.setFrom(tree.toSeriesFilters().withChoices(filters)) }
            }
        paged = Paged(text, filters, options)
        return options
    }
}
