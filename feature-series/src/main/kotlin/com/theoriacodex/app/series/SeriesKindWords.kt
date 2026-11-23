package com.theoriacodex.app.series

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.seriesKind
import java.util.Locale

/**
 * The words the Series screens use for a series' entries, which differ by its kind: chapters that are read, or
 * episodes that are watched. Every string that names an entry is built here, so the two kinds read alike; the app's
 * new-entries notification uses them too.
 */
enum class EntryWords(
    /** "chapter", "episode". */
    val one: String,
    /** "chapters", "episodes". */
    val many: String,
    /** What a finished entry is: "read", "watched". */
    val done: String,
    /** What an entry not yet finished is: "unread", "unwatched". */
    val notDone: String,
    /** What the user does with an entry: "reading", "watching". */
    val doing: String,
) {
    CHAPTERS("chapter", "chapters", "read", "unread", "reading"),
    EPISODES("episode", "episodes", "watched", "unwatched", "watching"),
    ;

    /** "1 chapter", "12 episodes". */
    fun count(count: Int): String = if (count == 1) "1 $one" else "$count $many"

    /** "1 new chapter", "3 new episodes". */
    fun new(count: Int): String = if (count == 1) "1 new $one" else "$count new $many"

    val none: String get() = "No $many"

    /** A finished entry's label: "Read", "Watched". */
    val finished: String get() = done.capitalized()

    /** What a cover's new-entry badge says to accessibility services. */
    fun unfinished(count: Int): String = "$count $notDone"

    /** Why a switch of source did not happen: the series switched to lists no entries to carry the progress over to. */
    val noneToMoveTo: String get() = "No $many to move your progress to"
}

/** "chapter" as a label's first word: "Chapter". */
internal fun String.capitalized(): String = replaceFirstChar { it.titlecase(Locale.ROOT) }

val SeriesKind.words: EntryWords
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
