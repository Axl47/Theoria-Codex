package com.theoriacodex.app.series

import android.content.Context
import androidx.core.content.edit
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.domain.model.SortChoice
import com.theoriacodex.domain.model.SortDirection
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SeriesView(val label: String) {
    FOLLOWING("Following"),
    BROWSE("Browse"),
}

enum class LibrarySort(val natural: SortDirection) {
    UNREAD(SortDirection.DESCENDING),
    LAST_UPDATED(SortDirection.DESCENDING),
    RECENTLY_READ(SortDirection.DESCENDING),
    RECENTLY_ADDED(SortDirection.DESCENDING),
    TITLE(SortDirection.ASCENDING),
    ;

    fun label(words: EntryWords): String = when (this) {
        UNREAD -> words.notDone.capitalized()
        LAST_UPDATED -> "Last updated"
        RECENTLY_READ -> "Last ${words.done}"
        RECENTLY_ADDED -> "Date added"
        TITLE -> "Title"
    }

    fun choice(): SortChoice<LibrarySort> = SortChoice(this, natural)
}

enum class LibraryShow {
    ALL,
    UNREAD,
    NOT_STARTED,
    IN_PROGRESS,
    CAUGHT_UP,
    COMPLETED,
    DOWNLOADED,
    ;

    fun label(words: EntryWords): String = when (this) {
        ALL -> "All"
        UNREAD -> words.notDone.capitalized()
        NOT_STARTED -> "Not started"
        IN_PROGRESS -> "In progress"
        CAUGHT_UP -> "Caught up"
        COMPLETED -> "Completed"
        DOWNLOADED -> "Downloaded"
    }

    fun admits(item: FollowedSeries, downloaded: Int): Boolean = when (this) {
        ALL -> true
        UNREAD -> item.unreadCount > 0
        NOT_STARTED -> !item.started
        IN_PROGRESS -> item.started && item.unreadCount > 0
        CAUGHT_UP -> item.started && item.unreadCount == 0
        COMPLETED -> item.series.status == SeriesStatus.COMPLETED
        DOWNLOADED -> downloaded > 0
    }
}

data class LibraryOptions(val sort: SortChoice<LibrarySort> = LibrarySort.UNREAD.choice(), val show: LibraryShow = LibraryShow.ALL) {
    val isDefault: Boolean get() = this == LibraryOptions()

    fun picking(sort: LibrarySort): LibraryOptions = copy(sort = this.sort.next(sort) { it.natural })

    fun reversed(): LibraryOptions = picking(sort.key)
}

data class CardCounts(val unread: Int, val downloaded: Int)

internal fun FollowedSeries.cardCounts(show: LibraryShow, downloads: Map<SeriesId, Int>): CardCounts =
    CardCounts(unread = unreadCount, downloaded = if (show == LibraryShow.DOWNLOADED) downloads[series.id] ?: 0 else 0)

internal fun List<FollowedSeries>.arranged(options: LibraryOptions, query: String, downloads: Map<SeriesId, Int> = emptyMap()): List<FollowedSeries> {
    val needle = query.trim()
    val shown = filter { item ->
        options.show.admits(item, downloads[item.series.id] ?: 0) && (needle.isEmpty() || item.series.title.contains(needle, ignoreCase = true))
    }
    val sort = options.sort.key
    val natural = when (sort) {
        LibrarySort.UNREAD -> shown
        LibrarySort.LAST_UPDATED -> shown.sortedByDescending { it.lastUpdatedAtEpochMs ?: Long.MIN_VALUE }
        LibrarySort.RECENTLY_READ -> shown.sortedByDescending { it.lastReadAtEpochMs ?: Long.MIN_VALUE }
        LibrarySort.RECENTLY_ADDED -> shown.sortedByDescending { it.followedAtEpochMs ?: Long.MIN_VALUE }
        LibrarySort.TITLE -> shown.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.series.title })
    }
    return if (options.sort.direction == sort.natural) natural else natural.asReversed()
}

interface SeriesLibraryPreferences {
    suspend fun read(): LibraryOptions
    fun write(options: LibraryOptions)
}

object NoOpSeriesLibraryPreferences : SeriesLibraryPreferences {
    override suspend fun read(): LibraryOptions = LibraryOptions()
    override fun write(options: LibraryOptions) = Unit
}

class SharedPreferencesSeriesLibraryPreferences(context: Context) : SeriesLibraryPreferences {
    private val prefs by lazy { context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override suspend fun read(): LibraryOptions = withContext(Dispatchers.IO) {
        val sort = LibrarySort.entries.firstOrNull { it.name == prefs.getString(SORT, null) } ?: LibrarySort.UNREAD
        LibraryOptions(
            sort = SortChoice(sort, SortDirection.entries.firstOrNull { it.name == prefs.getString(DIRECTION, null) } ?: sort.natural),
            show = LibraryShow.entries.firstOrNull { it.name == prefs.getString(SHOW, null) } ?: LibraryShow.ALL,
        )
    }

    override fun write(options: LibraryOptions) = prefs.edit {
        putString(SORT, options.sort.key.name)
        putString(DIRECTION, (options.sort.direction ?: options.sort.key.natural).name)
        putString(SHOW, options.show.name)
    }

    private companion object {
        const val PREFS_NAME = "series_library_options"
        const val SORT = "sort"
        const val SHOW = "show"
        const val DIRECTION = "direction"
    }
}
