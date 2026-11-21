package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.CodexLibrarySort
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.model.Codex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal val CodexLibrarySort.label: String
    get() = when (this) {
        CodexLibrarySort.MANUAL -> "Manual"
        CodexLibrarySort.NAME_ASCENDING -> "Name (A–Z)"
        CodexLibrarySort.NAME_DESCENDING -> "Name (Z–A)"
        CodexLibrarySort.NEWEST -> "Recently added"
        CodexLibrarySort.OLDEST -> "Oldest first"
        CodexLibrarySort.MOST_ITEMS -> "Most posts"
        CodexLibrarySort.FEWEST_ITEMS -> "Fewest posts"
        CodexLibrarySort.MOST_WATCHED -> "Most watched"
        CodexLibrarySort.MOST_OPENED -> "Most opened"
    }

internal val CodexLibrarySort.usesItemCounts: Boolean
    get() = this == CodexLibrarySort.MOST_ITEMS || this == CodexLibrarySort.FEWEST_ITEMS

val CodexLibrarySort.usesUsage: Boolean
    get() = this == CodexLibrarySort.MOST_WATCHED || this == CodexLibrarySort.MOST_OPENED

/**
 * Usage per Codex id, emitting only when a figure changes because other statistics are written often.
 * A statistics failure degrades to no usage, leaving manual order, and never affects the Library.
 */
fun StatisticsRepository.observeCodexLibraryUsage(): Flow<Map<String, CodexUsage>> =
    observeStatistics()
        .map { statistics ->
            (statistics.codexStatistics.keys + statistics.codexEntryCounts.keys).associateWith { codexId ->
                CodexUsage(
                    watchingMs = statistics.codexStatistics[codexId]?.watchingMs ?: 0L,
                    opens = statistics.codexEntryCounts[codexId] ?: 0L,
                )
            }
        }
        .distinctUntilChanged()
        .catch { emit(emptyMap()) }

/**
 * Orders [codices], given in manual order, for the Library and Add to Codex. Automatic sorts pin
 * Likes first because it presents as the full-width Saved tile. Usage sorts break ties on the other
 * usage figure; remaining ties keep their manual order.
 */
internal fun sortCodicesForLibrary(
    codices: List<Codex>,
    sort: CodexLibrarySort,
    itemCounts: Map<String, Int>,
    likesCodexId: String?,
    usage: Map<String, CodexUsage> = emptyMap(),
): List<Codex> {
    val itemCount = { codex: Codex -> itemCounts[codex.codexId] ?: 0 }
    val usageOf = { codex: Codex -> usage[codex.codexId] ?: CodexUsage() }
    val comparator = when (sort) {
        CodexLibrarySort.MANUAL -> return codices
        CodexLibrarySort.NAME_ASCENDING -> compareBy(String.CASE_INSENSITIVE_ORDER, Codex::name)
        CodexLibrarySort.NAME_DESCENDING -> compareByDescending(String.CASE_INSENSITIVE_ORDER, Codex::name)
        CodexLibrarySort.NEWEST -> compareByDescending(Codex::createdAtEpochMs)
        CodexLibrarySort.OLDEST -> compareBy(Codex::createdAtEpochMs)
        CodexLibrarySort.MOST_ITEMS -> compareByDescending(itemCount)
        CodexLibrarySort.FEWEST_ITEMS -> compareBy(itemCount)
        CodexLibrarySort.MOST_WATCHED -> compareBy(CodexUsage.MostWatched, usageOf)
        CodexLibrarySort.MOST_OPENED -> compareBy(CodexUsage.MostOpened, usageOf)
    }
    val (likes, others) = codices.partition { it.codexId == likesCodexId }
    return likes + others.sortedWith(comparator)
}
