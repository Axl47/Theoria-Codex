package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.CodexLibrarySort
import com.theoriacodex.domain.model.Codex

internal val CodexLibrarySort.label: String
    get() = when (this) {
        CodexLibrarySort.MANUAL -> "Manual"
        CodexLibrarySort.NAME_ASCENDING -> "Name (A–Z)"
        CodexLibrarySort.NAME_DESCENDING -> "Name (Z–A)"
        CodexLibrarySort.NEWEST -> "Recently added"
        CodexLibrarySort.OLDEST -> "Oldest first"
        CodexLibrarySort.MOST_ITEMS -> "Most items"
        CodexLibrarySort.FEWEST_ITEMS -> "Fewest items"
    }

internal val CodexLibrarySort.usesItemCounts: Boolean
    get() = this == CodexLibrarySort.MOST_ITEMS || this == CodexLibrarySort.FEWEST_ITEMS

/**
 * Orders [codices], given in manual order, for the Library and Add to Codex. Automatic sorts pin
 * Likes first because it presents as the full-width Saved tile; ties keep their manual order.
 */
internal fun sortCodicesForLibrary(
    codices: List<Codex>,
    sort: CodexLibrarySort,
    itemCounts: Map<String, Int>,
    likesCodexId: String?,
): List<Codex> {
    val itemCount = { codex: Codex -> itemCounts[codex.codexId] ?: 0 }
    val comparator = when (sort) {
        CodexLibrarySort.MANUAL -> return codices
        CodexLibrarySort.NAME_ASCENDING -> compareBy(String.CASE_INSENSITIVE_ORDER, Codex::name)
        CodexLibrarySort.NAME_DESCENDING -> compareByDescending(String.CASE_INSENSITIVE_ORDER, Codex::name)
        CodexLibrarySort.NEWEST -> compareByDescending(Codex::createdAtEpochMs)
        CodexLibrarySort.OLDEST -> compareBy(Codex::createdAtEpochMs)
        CodexLibrarySort.MOST_ITEMS -> compareByDescending(itemCount)
        CodexLibrarySort.FEWEST_ITEMS -> compareBy(itemCount)
    }
    val (likes, others) = codices.partition { it.codexId == likesCodexId }
    return likes + others.sortedWith(comparator)
}
