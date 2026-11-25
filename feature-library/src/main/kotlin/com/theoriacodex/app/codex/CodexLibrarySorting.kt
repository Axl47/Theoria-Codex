package com.theoriacodex.app.codex

import com.theoriacodex.app.ui.components.SortOption
import com.theoriacodex.data.repository.CodexLibrarySort
import com.theoriacodex.data.repository.CodexLibrarySortKey
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.sortKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal val CodexLibrarySortOptions: List<SortOption<CodexLibrarySortKey>> = CodexLibrarySort.entries.sortKeys().map { key ->
    SortOption(
        key,
        when (key) {
            CodexLibrarySortKey.MANUAL -> "Manual"
            CodexLibrarySortKey.NAME -> "Name"
            CodexLibrarySortKey.DATE_ADDED -> "Date added"
            CodexLibrarySortKey.POSTS -> "Posts"
            CodexLibrarySortKey.WATCH_TIME -> "Watch time"
            CodexLibrarySortKey.OPENS -> "Opens"
        },
    )
}

internal val CodexLibrarySort.usesItemCounts: Boolean
    get() = choice.key == CodexLibrarySortKey.POSTS

val CodexLibrarySort.usesUsage: Boolean
    get() = choice.key == CodexLibrarySortKey.WATCH_TIME || choice.key == CodexLibrarySortKey.OPENS

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
        CodexLibrarySort.LEAST_WATCHED -> compareBy(CodexUsage.MostWatched.reversed(), usageOf)
        CodexLibrarySort.MOST_OPENED -> compareBy(CodexUsage.MostOpened, usageOf)
        CodexLibrarySort.LEAST_OPENED -> compareBy(CodexUsage.MostOpened.reversed(), usageOf)
    }
    val (likes, others) = codices.partition { it.codexId == likesCodexId }
    return likes + others.sortedWith(comparator)
}
