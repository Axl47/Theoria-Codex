package com.theoriacodex.app.series

import android.content.Context
import androidx.core.content.edit
import com.theoriacodex.domain.series.SeriesId

data class ChapterDisplayPreferences(
    val sort: ChapterSort = ChapterSort.SOURCE_ORDER,
    val descending: Boolean = true,
    val filter: ChapterFilter = ChapterFilter.ALL,
) {
    val isDefault: Boolean
        get() = sort == ChapterSort.SOURCE_ORDER && descending && filter == ChapterFilter.ALL
}

interface SeriesChapterPreferences {
    fun read(id: SeriesId): ChapterDisplayPreferences
    fun write(id: SeriesId, preferences: ChapterDisplayPreferences)
}

object NoOpSeriesChapterPreferences : SeriesChapterPreferences {
    override fun read(id: SeriesId): ChapterDisplayPreferences = ChapterDisplayPreferences()
    override fun write(id: SeriesId, preferences: ChapterDisplayPreferences) = Unit
}

class SharedPreferencesSeriesChapterPreferences(context: Context) : SeriesChapterPreferences {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(id: SeriesId): ChapterDisplayPreferences {
        val raw = prefs.getString(keyFor(id), null) ?: return ChapterDisplayPreferences()
        val parts = raw.split('|')
        if (parts.size != 3) return ChapterDisplayPreferences()
        val sort = ChapterSort.entries.firstOrNull { it.name == parts[0] } ?: ChapterSort.SOURCE_ORDER
        val descending = parts[1].toBooleanStrictOrNull() ?: true
        val filter = ChapterFilter.entries.firstOrNull { it.name == parts[2] } ?: ChapterFilter.ALL
        return ChapterDisplayPreferences(sort, descending, filter)
    }

    override fun write(id: SeriesId, preferences: ChapterDisplayPreferences) {
        val key = keyFor(id)
        prefs.edit {
            if (preferences.isDefault) remove(key)
            else putString(key, "${preferences.sort.name}|${preferences.descending}|${preferences.filter.name}")
        }
    }

    private fun keyFor(id: SeriesId): String = "${id.source.id}|${id.address}"

    private companion object {
        const val PREFS_NAME = "series_chapter_options"
    }
}
