package com.theoriacodex.extensionhost

import com.theoriacodex.domain.series.Inclusion
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.withChoices
import eu.kanade.tachiyomi.source.model.Filter

/*
 * Both interfaces' filters are objects the source makes afresh for each search and reads back from; extensions subclass
 * them, so the host only reads and sets their `state`. Mihon's (`MihonFilters.kt`) and Aniyomi's (`AniyomiFilters.kt`)
 * trees have the same shapes under different class names, and the app's model mirrors them, so each tree is read into
 * [FilterNode]s and everything else here serves both.
 */

/** One filter of a source's tree: its value in the app's model, and how to set a chosen value back onto it. */
internal class FilterNode(val value: SeriesFilter, val set: (SeriesFilter) -> Unit = {})

/** A node that takes only a value of its own kind; a value of another kind is skipped rather than trusted. */
internal inline fun <reified V : SeriesFilter> filterNode(value: V, crossinline set: (V) -> Unit): FilterNode =
    FilterNode(value) { chosen -> (chosen as? V)?.let { set(it) } }

/** A group and its [members]: the group's members that are not filters are left out, in both directions. */
internal fun groupNode(name: String, members: List<FilterNode>): FilterNode =
    filterNode(SeriesFilter.Group(name, members.toSeriesFilters())) { members.setFrom(it.filters) }

internal fun List<FilterNode>.toSeriesFilters(): List<SeriesFilter> = map(FilterNode::value)

/**
 * Sets each filter from the value at its place in [values], which must have been made from this same tree
 * ([toSeriesFilters], then [withChoices]).
 */
internal fun List<FilterNode>.setFrom(values: List<SeriesFilter>) {
    zip(values).forEach { (node, value) -> node.set(value) }
}

/** A tri-state filter's state; Aniyomi numbers its states as Mihon does. */
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

/**
 * A source's searches: [make] is the source's own filter list, made afresh, and [nodes] reads one. A first page sets
 * the values chosen onto a new list; later pages of the same search hand the source that list again, as Mihon and
 * Aniyomi do, since a source that fills its filters in after its first request would otherwise page with other criteria.
 */
internal class FilterSearch<L>(private val make: () -> L, private val nodes: (L) -> List<FilterNode>) {
    /** The last search asked for and the list its pages were given; another search replaces it. */
    private class Paged<L>(val text: String, val filters: List<SeriesFilter>, val options: L)

    @Volatile private var paged: Paged<L>? = null

    /** The source's filters at their defaults. */
    fun defaults(): List<SeriesFilter> = nodes(make()).toSeriesFilters()

    /** The list to hand the source for [page] of the search for [text] with [filters] chosen. */
    fun options(text: String, page: Int, filters: List<SeriesFilter>): L {
        val options = paged?.takeIf { page > 1 && it.text == text && it.filters == filters }?.options
            ?: make().also { options ->
                if (filters.isNotEmpty()) nodes(options).let { tree -> tree.setFrom(tree.toSeriesFilters().withChoices(filters)) }
            }
        paged = Paged(text, filters, options)
        return options
    }
}
