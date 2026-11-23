package com.theoriacodex.app.series

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.seriesKind

/**
 * The words the Series screens use for a series' entries, which differ by its kind: chapters that are read, or
 * episodes that are watched. Every string that names an entry is built here, so the two kinds read alike.
 */
internal enum class EntryWords(private val one: String, private val many: String, val finished: String, private val unfinished: String) {
    CHAPTERS("chapter", "chapters", "Read", "unread"),
    EPISODES("episode", "episodes", "Watched", "unwatched"),
    ;

    /** "1 chapter", "12 episodes". */
    fun count(count: Int): String = if (count == 1) "1 $one" else "$count $many"

    val none: String get() = "No $many"

    /** What a cover's new-entry badge says to accessibility services. */
    fun unfinished(count: Int): String = "$count $unfinished"

    /** Why a switch of source did not happen: the series switched to lists no entries to carry the progress over to. */
    val noneToMoveTo: String get() = "No $many to move your progress to"
}

internal val SeriesKind.words: EntryWords
    get() = when (this) {
        SeriesKind.MANGA -> EntryWords.CHAPTERS
        SeriesKind.ANIME -> EntryWords.EPISODES
    }

/** What the kind filter and the Extensions page call this kind. */
internal val SeriesKind.label: String
    get() = when (this) {
        SeriesKind.MANGA -> "Manga"
        SeriesKind.ANIME -> "Anime"
    }

/** Whether a tab narrowed to this kind shows what [key]'s source lists; null, no narrowing, shows everything. */
internal fun SeriesKind?.admits(key: SourceKey): Boolean = this == null || key.seriesKind == this
