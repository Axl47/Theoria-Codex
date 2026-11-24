package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.seriesKind
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
    /**
     * Whether two of its chapters were found to open on the same page, as a group's credit page does, so the Viewer's
     * strip pictures each chapter by its second page. Stays set once set.
     */
    val chaptersShareFirstPage: Boolean = false,
    /** When the user last followed it; null while not followed, and for some followed before the time was kept. */
    val followedAtEpochMs: Long? = null,
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
    /** When the user last opened it or moved through it; null before that was kept. Marking it read does not count. */
    val lastReadAtEpochMs: Long? = null,
)

/**
 * A followed series as the Following list shows it. [lastUpdatedAtEpochMs] is when its newest listed entry came out,
 * by the source's upload dates, or, from a source that gives none, when the newest was first seen; null with no
 * entries stored. [lastReadAtEpochMs] is the latest of its entries'. [started] is whether any entry was opened or read.
 */
data class FollowedSeries(
    val series: Series,
    val unreadCount: Int,
    val followedAtEpochMs: Long? = null,
    val lastReadAtEpochMs: Long? = null,
    val lastUpdatedAtEpochMs: Long? = null,
    val started: Boolean = false,
)

/**
 * Exact prior progress of the entries a bulk read/unread write changed, captured inside the write's transaction so
 * Undo restores only what that operation touched.
 */
data class EntryProgressRestore(
    val entries: List<StoredSeriesEntry> = emptyList(),
    val restoreLastOpened: Boolean = false,
    val lastOpenedEntry: String? = null,
) {
    val isEmpty: Boolean get() = entries.isEmpty() && !restoreLastOpened
}

/**
 * Stored series, their entries and reading progress. A series is stored when its page is opened; following only
 * flags it. Page addresses are never stored, since they expire; downloaded pages are [SeriesDownloads]' files.
 */
interface SeriesRepository {
    /** Series with unread chapters first (most unread first), then by title. */
    fun observeFollowed(): Flow<List<FollowedSeries>>

    fun observeSeries(id: SeriesId): Flow<StoredSeries?>

    /** In the source's order, newest first. */
    fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>>

    suspend fun series(id: SeriesId): StoredSeries?

    suspend fun entries(id: SeriesId): List<StoredSeriesEntry>

    /**
     * Stores what the source says about [series], keeping what the user did with it, and the stored description,
     * status and memo when [series] has none. With [entries], the source's current list replaces the stored one:
     * entries it kept keep their progress, first-seen time and, when listed without one, memo; ones it dropped leave
     * the list but are remembered, so one that returns is as the user left it. An empty list changes nothing, since a
     * source that fails quietly lists none.
     *
     * Returns the new entries, in the source's order: those whose address was never stored, unless one is plainly a
     * dropped entry at a new address. That takes both to be the only release of their chapter number by their
     * translator group, among the dropped entries and among the listed ones; the new address then starts with the
     * dropped one's progress. A series with no stored entries gets none from its first list, because nothing was there
     * to compare it with; one imported with its entries compares against those.
     */
    suspend fun store(series: Series, entries: List<SeriesEntry>? = null): List<SeriesEntry>

    /**
     * Replaces the stored entry list of the series [id] with [entries], as [store] does, leaving what is stored about
     * the series itself as it is apart from its memo, which [seriesMemo] replaces when given. Does nothing for a series
     * that is not stored.
     */
    suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>, seriesMemo: String? = null): List<SeriesEntry>

    suspend fun setFollowed(id: SeriesId, followed: Boolean)

    suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode)

    suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean)

    /** Records that two of [id]'s chapters open on the same page. Does nothing for a series that is not stored. */
    suspend fun setChaptersShareFirstPage(id: SeriesId)

    /**
     * Marks every listed entry of [id] as read or unread and returns the prior state of only the entries changed.
     * Marking unread also resets partial progress and clears last-opened entry.
     */
    suspend fun setAllRead(id: SeriesId, read: Boolean): EntryProgressRestore

    /**
     * Marks the listed entries at [entryAddresses] as read or unread and returns the prior state of only the entries
     * changed. Marking unread resets their partial progress.
     */
    suspend fun setEntriesRead(id: SeriesId, entryAddresses: List<String>, read: Boolean): EntryProgressRestore

    /** Restores only the snapshotted entries and (when requested) last-opened entry from [restore]. */
    suspend fun restoreProgress(id: SeriesId, restore: EntryProgressRestore)

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
 * reached; an episode once [ANIME_FINISHED_PERCENT] of it was watched, as Aniyomi counts it. Either stays read. An
 * episode whose [length] is not known yet (zero) records nothing, so it is never finished by opening it.
 */
fun StoredSeriesEntry.withProgress(position: Long, length: Long, kind: SeriesKind): StoredSeriesEntry = when (kind) {
    SeriesKind.MANGA -> {
        val reached = position.coerceIn(1, length.coerceAtLeast(1))
        copy(position = reached, length = length, read = read || reached >= length)
    }
    SeriesKind.ANIME -> if (length <= 0) this else {
        val reached = position.coerceIn(0, length)
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
    // Where an untouched entry stands: page 1 of a chapter, the start of an episode.
    val started = if (series.series.id.source.seriesKind == SeriesKind.ANIME) 0L else 1L
    val opened = ordered.indexOfFirst { it.entry.address == series.lastOpenedEntry }
    val lastOpened = ordered.getOrNull(opened)
    return lastOpened?.takeIf { !it.read }
        ?: lastOpened?.let { ordered.getOrNull(opened + 1) }?.takeIf { !it.read }
        ?: ordered.lastOrNull { !it.read && it.position > started }
        ?: ordered.firstOrNull { !it.read }
        ?: lastOpened
        ?: ordered.lastOrNull()
}

/** The page (from 0) a chapter opens at: where the user left an unfinished chapter, the start otherwise. */
fun StoredSeriesEntry.resumePageIndex(): Int = if (read) 0 else (position - 1).coerceAtLeast(0).toInt()

/**
 * The playback position, in milliseconds, an episode starts at: where the user left it, while that is short of the
 * point that counts as watching it ([ANIME_FINISHED_PERCENT]), else 0. A watched episode left partway through a rewatch
 * resumes too.
 */
fun StoredSeriesEntry.resumePositionMs(): Long {
    val length = length ?: return if (read) 0 else position
    return if (position * 100 < length * ANIME_FINISHED_PERCENT) position else 0
}
