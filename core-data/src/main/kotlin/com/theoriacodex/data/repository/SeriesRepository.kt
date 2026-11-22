package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.flow.Flow

/** A series as stored: what its source said, and what the user did with it. */
data class StoredSeries(
    val series: Series,
    val followed: Boolean = false,
    /** The reading mode the user chose for this series; null follows the guess. */
    val readingMode: GalleryReadingMode? = null,
    /** The address of the entry opened last. */
    val lastOpenedEntry: String? = null,
    /** When the source last listed this series' entries. */
    val lastCheckedAtEpochMs: Long? = null,
)

/** An entry as stored, with the user's progress through it. */
data class StoredSeriesEntry(
    val entry: SeriesEntry,
    /** The entry's place in its source's list, which runs newest first. */
    val sourceOrder: Int,
    val firstSeenAtEpochMs: Long,
    val read: Boolean = false,
    /** The page the user was on when they left, counted from 1; 0 before the entry was ever opened. */
    val lastPage: Int = 0,
    /** Known once the entry has been opened. */
    val pageCount: Int? = null,
)

data class FollowedSeries(val series: Series, val unreadCount: Int)

/**
 * Stored series, their entries and reading progress. A series is stored when its page is opened; following only
 * flags it. Pages are never stored: their addresses expire.
 */
interface SeriesRepository {
    /** By title. */
    fun observeFollowed(): Flow<List<FollowedSeries>>

    fun observeSeries(id: SeriesId): Flow<StoredSeries?>

    /** In the source's order, newest first. */
    fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>>

    suspend fun series(id: SeriesId): StoredSeries?

    suspend fun entries(id: SeriesId): List<StoredSeriesEntry>

    /**
     * Stores what the source says about [series], keeping what the user did with it, and the stored description and
     * status when [series] has none. With [entries], the source's
     * current list replaces the stored one: entries it kept keep their progress and first-seen time, and ones it
     * dropped leave the list but are remembered, so one that returns is as the user left it. An empty list changes
     * nothing, since a source that fails quietly lists none.
     *
     * Returns the new entries, in the source's order: those whose address was never stored, unless one is plainly a
     * dropped entry at a new address. That takes both to be the only release of their chapter number by their
     * translator group, among the dropped entries and among the listed ones; the new address then starts with the
     * dropped one's progress. The first list a series ever gets has no new entries, because nothing was there to
     * compare it with.
     */
    suspend fun store(series: Series, entries: List<SeriesEntry>? = null): List<SeriesEntry>

    /**
     * Replaces the stored entry list of the series [id] with [entries], as [store] does, leaving what is stored about
     * the series itself as it is. Does nothing for a series that is not stored.
     */
    suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>): List<SeriesEntry>

    suspend fun setFollowed(id: SeriesId, followed: Boolean)

    suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode)

    /**
     * The user is on [page] (from 1) of the entry at [entryAddress]. [pageCount] is null while the entry's pages are
     * still loading, when only the series' last opened entry is recorded.
     */
    suspend fun recordProgress(id: SeriesId, entryAddress: String, page: Int, pageCount: Int?)
}

/** [this] after the user reached [page] of [pageCount]: an entry is read once its last page was reached, and stays read. */
fun StoredSeriesEntry.withProgress(page: Int, pageCount: Int): StoredSeriesEntry {
    val reached = page.coerceIn(1, pageCount.coerceAtLeast(1))
    return copy(lastPage = reached, pageCount = pageCount, read = read || reached >= pageCount)
}

/** [entries] oldest first, the order they are read in. */
fun List<StoredSeriesEntry>.inReadingOrder(): List<StoredSeriesEntry> = sortedByDescending { it.sourceOrder }

/**
 * Where Continue resumes, the first of these that exists: the entry opened last, while it is unfinished; the entry
 * after it in reading order, while unread; the latest entry the user is partway through (past its first page); the
 * first unread entry in reading order, which is the first entry while nothing is read; and, with everything read, the
 * entry opened last. Null when the series has no entries.
 */
fun continueEntry(series: StoredSeries, entries: List<StoredSeriesEntry>): StoredSeriesEntry? {
    val ordered = entries.inReadingOrder()
    val opened = ordered.indexOfFirst { it.entry.address == series.lastOpenedEntry }
    val lastOpened = ordered.getOrNull(opened)
    return lastOpened?.takeIf { !it.read }
        ?: lastOpened?.let { ordered.getOrNull(opened + 1) }?.takeIf { !it.read }
        ?: ordered.lastOrNull { !it.read && it.lastPage > 1 }
        ?: ordered.firstOrNull { !it.read }
        ?: lastOpened
        ?: ordered.lastOrNull()
}

/** The page (from 0) an entry opens at: where the user left an unfinished entry, the start otherwise. */
fun StoredSeriesEntry.resumePageIndex(): Int = if (read) 0 else (lastPage - 1).coerceAtLeast(0)
