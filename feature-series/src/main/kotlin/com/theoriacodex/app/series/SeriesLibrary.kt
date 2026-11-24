package com.theoriacodex.app.series

import android.content.Context
import androidx.core.content.edit
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.domain.series.SeriesStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Series tab's two views: the followed series, and one source's series to browse. */
enum class SeriesView(val label: String) {
    FOLLOWING("Following"),
    BROWSE("Browse"),
}

/** How the Following grid is ordered. Unread is the stored order: series with unread entries first, most first. */
enum class LibrarySort {
    UNREAD,
    LAST_UPDATED,
    RECENTLY_READ,
    RECENTLY_ADDED,
    TITLE,
    ;

    fun label(words: EntryWords): String = when (this) {
        UNREAD -> "Most ${words.notDone}"
        LAST_UPDATED -> "Last updated"
        RECENTLY_READ -> "Recently ${words.done}"
        RECENTLY_ADDED -> "Recently added"
        TITLE -> "Title"
    }
}

/** Which followed series the Following grid shows. */
enum class LibraryShow {
    ALL,
    UNREAD,
    NOT_STARTED,
    IN_PROGRESS,
    CAUGHT_UP,
    COMPLETED,
    ;

    fun label(words: EntryWords): String = when (this) {
        ALL -> "All"
        UNREAD -> words.notDone.capitalized()
        NOT_STARTED -> "Not started"
        IN_PROGRESS -> "In progress"
        CAUGHT_UP -> "Caught up"
        COMPLETED -> "Completed"
    }

    fun admits(item: FollowedSeries): Boolean = when (this) {
        ALL -> true
        UNREAD -> item.unreadCount > 0
        NOT_STARTED -> !item.started
        IN_PROGRESS -> item.started && item.unreadCount > 0
        CAUGHT_UP -> item.started && item.unreadCount == 0
        COMPLETED -> item.series.status == SeriesStatus.COMPLETED
    }
}

data class LibraryOptions(val sort: LibrarySort = LibrarySort.UNREAD, val show: LibraryShow = LibraryShow.ALL) {
    val isDefault: Boolean get() = this == LibraryOptions()
}

/**
 * The followed series [options] show, in their order, whose title holds [query]. Ties, and series a time sort has no
 * time for (placed last), keep the stored order.
 */
internal fun List<FollowedSeries>.arranged(options: LibraryOptions, query: String): List<FollowedSeries> {
    val needle = query.trim()
    val shown = filter { options.show.admits(it) && (needle.isEmpty() || it.series.title.contains(needle, ignoreCase = true)) }
    return when (options.sort) {
        LibrarySort.UNREAD -> shown
        LibrarySort.LAST_UPDATED -> shown.sortedByDescending { it.lastUpdatedAtEpochMs ?: Long.MIN_VALUE }
        LibrarySort.RECENTLY_READ -> shown.sortedByDescending { it.lastReadAtEpochMs ?: Long.MIN_VALUE }
        LibrarySort.RECENTLY_ADDED -> shown.sortedByDescending { it.followedAtEpochMs ?: Long.MIN_VALUE }
        LibrarySort.TITLE -> shown.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.series.title })
    }
}

/** Remembers the Following grid's sort and filter across launches. Reading may touch the disk. */
interface SeriesLibraryPreferences {
    suspend fun read(): LibraryOptions
    fun write(options: LibraryOptions)
}

/** For tests and previews: always the defaults, and nothing kept. */
object NoOpSeriesLibraryPreferences : SeriesLibraryPreferences {
    override suspend fun read(): LibraryOptions = LibraryOptions()
    override fun write(options: LibraryOptions) = Unit
}

class SharedPreferencesSeriesLibraryPreferences(context: Context) : SeriesLibraryPreferences {
    // Opened by the first read, off the main thread; a write comes after it.
    private val prefs by lazy { context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override suspend fun read(): LibraryOptions = withContext(Dispatchers.IO) {
        LibraryOptions(
            sort = LibrarySort.entries.firstOrNull { it.name == prefs.getString(SORT, null) } ?: LibrarySort.UNREAD,
            show = LibraryShow.entries.firstOrNull { it.name == prefs.getString(SHOW, null) } ?: LibraryShow.ALL,
        )
    }

    override fun write(options: LibraryOptions) = prefs.edit {
        putString(SORT, options.sort.name)
        putString(SHOW, options.show.name)
    }

    private companion object {
        const val PREFS_NAME = "series_library_options"
        const val SORT = "sort"
        const val SHOW = "show"
    }
}
