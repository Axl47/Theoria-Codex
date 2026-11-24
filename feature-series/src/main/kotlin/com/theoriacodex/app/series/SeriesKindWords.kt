package com.theoriacodex.app.series

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.seriesKind
import java.util.Locale

enum class EntryWords(
    val one: String,
    val many: String,
    val done: String,
    val notDone: String,
    val doing: String,
    val open: String,
) {
    CHAPTERS("chapter", "chapters", "read", "unread", "reading", "Read"),
    EPISODES("episode", "episodes", "watched", "unwatched", "watching", "Watch"),
    ;

    fun count(count: Int): String = if (count == 1) "1 $one" else "$count $many"

    fun new(count: Int): String = if (count == 1) "1 new $one" else "$count new $many"

    val none: String get() = "No $many"

    val finished: String get() = done.capitalized()

    fun unfinished(count: Int): String = "$count $notDone"

    val noneToMoveTo: String get() = "No $many to move your progress to"
}

internal fun String.capitalized(): String = replaceFirstChar { it.titlecase(Locale.ROOT) }

val SeriesKind.words: EntryWords
    get() = when (this) {
        SeriesKind.MANGA -> EntryWords.CHAPTERS
        SeriesKind.ANIME -> EntryWords.EPISODES
    }

val SeriesKind.label: String
    get() = when (this) {
        SeriesKind.MANGA -> "Manga"
        SeriesKind.ANIME -> "Anime"
    }

internal fun SeriesKind?.admits(key: SourceKey): Boolean = this == null || key.seriesKind == this
