package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
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
    /** Whether chapters the check for new chapters finds are downloaded, while the series is followed. */
    val downloadNewChapters: Boolean = false,
)

/**
 * An entry as stored, with the user's progress through it. [position] and [length] are one pair for both kinds, in the
 * unit the series' [SeriesKind] fixes: a page (from 1) of a page count for manga, milliseconds for anime. Whatever does
 * not read their size (started, Continue, a switch of source) treats them alike.
 */
data class StoredSeriesEntry(
    val entry: SeriesEntry,
    /** The entry's place in its source's list, which runs newest first. */
    val sourceOrder: Int,
    val firstSeenAtEpochMs: Long,
    /** Finished: every page reached, or most of the episode watched. Stays set once set. */
    val read: Boolean = false,
    /** Where the user was when they left; 0 before the entry was ever opened. */
    val position: Long = 0,
    /** The entry's page count or duration; known once the entry has been opened. */
    val length: Long? = null,
)

data class FollowedSeries(val series: Series, val unreadCount: Int)

/**
 * Stored series, their entries and reading progress. A series is stored when its page is opened; following only
 * flags it. Page addresses are never stored, since they expire; downloaded pages are [SeriesDownloads]' files.
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

    suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean)

    /**
     * The user is at [position] of [length] in the entry at [entryAddress], in the unit its series' kind fixes (see
     * [StoredSeriesEntry]); whether that finishes the entry is [withProgress]' rule. [length] is null while the entry's
     * media is still loading, when only the series' last opened entry is recorded.
     */
    suspend fun recordProgress(id: SeriesId, entryAddress: String, position: Long, length: Long?)

    /**
     * Replaces the stored series [from] with [to], the same work on another source, in one step: stores [to] with
     * [entries] as [store] does, carries [from]'s settings and progress over by [carryProgress], and removes [from] with
     * its entries. [to]'s entries are all stored as seen, so only entries listed after the move count as new. Returns
     * whether it moved: nothing changes when [from] is not stored, as when another move took it first, or is [to].
     * Throws [NoChaptersToMoveToException], changing nothing, when [entries] is empty while [from] has progress.
     */
    suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>): Boolean
}

/**
 * [this] after the user reached [position] of [length] in a series of [kind]. A chapter is read once its last page was
 * reached; an episode once [ANIME_FINISHED_PERCENT] of it was watched, as Aniyomi counts it. Either stays read.
 */
fun StoredSeriesEntry.withProgress(position: Long, length: Long, kind: SeriesKind): StoredSeriesEntry = when (kind) {
    SeriesKind.MANGA -> {
        val reached = position.coerceIn(1, length.coerceAtLeast(1))
        copy(position = reached, length = length, read = read || reached >= length)
    }
    SeriesKind.ANIME -> {
        val reached = position.coerceIn(0, length.coerceAtLeast(0))
        copy(position = reached, length = length, read = read || reached * 100 >= length * ANIME_FINISHED_PERCENT)
    }
}

/** How much of an episode, in percent of its duration, counts as watching it. */
const val ANIME_FINISHED_PERCENT = 85

/** [entries] oldest first, the order they are read in. */
fun List<StoredSeriesEntry>.inReadingOrder(): List<StoredSeriesEntry> = sortedByDescending { it.sourceOrder }

/**
 * Where Continue resumes, the first of these that exists: the entry opened last, while it is unfinished; the entry
 * after it in reading order, while unread; the latest entry the user is partway through (past its first page, or its
 * first moment); the
 * first unread entry in reading order, which is the first entry while nothing is read; and, with everything read, the
 * entry opened last. Null when the series has no entries.
 */
fun continueEntry(series: StoredSeries, entries: List<StoredSeriesEntry>): StoredSeriesEntry? {
    val ordered = entries.inReadingOrder()
    val opened = ordered.indexOfFirst { it.entry.address == series.lastOpenedEntry }
    val lastOpened = ordered.getOrNull(opened)
    return lastOpened?.takeIf { !it.read }
        ?: lastOpened?.let { ordered.getOrNull(opened + 1) }?.takeIf { !it.read }
        ?: ordered.lastOrNull { !it.read && it.position > 1 }
        ?: ordered.firstOrNull { !it.read }
        ?: lastOpened
        ?: ordered.lastOrNull()
}

/** The page (from 0) a chapter opens at: where the user left an unfinished chapter, the start otherwise. */
fun StoredSeriesEntry.resumePageIndex(): Int = if (read) 0 else (position - 1).coerceAtLeast(0).toInt()

/** The playback position, in milliseconds, an episode starts at: where the user left an unfinished one, else 0. */
fun StoredSeriesEntry.resumePositionMs(): Long = if (read) 0 else position
