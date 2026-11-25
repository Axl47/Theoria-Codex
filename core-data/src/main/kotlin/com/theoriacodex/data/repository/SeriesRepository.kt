package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.seriesKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class StoredSeries(
    val series: Series,
    val followed: Boolean = false,
    val readingMode: GalleryReadingMode? = null,
    val lastOpenedEntry: String? = null,
    val downloadNewChapters: Boolean = false,
    val chaptersShareFirstPage: Boolean = false,
    val followedAtEpochMs: Long? = null,
    val checkedAtEpochMs: Long? = null,
)

data class StoredSeriesEntry(
    val entry: SeriesEntry,
    val sourceOrder: Int,
    val firstSeenAtEpochMs: Long,
    val read: Boolean = false,
    val position: Long = 0,
    val length: Long? = null,
    val lastReadAtEpochMs: Long? = null,
    val pageFraction: PageFraction? = null,
) {
    val hasProgress: Boolean get() = read || position > 0

    val pageOffset: Float? get() = pageFraction?.takeIf { it.page == position && position > 0 }?.fraction
}

data class PageFraction(val page: Long, val fraction: Float) {
    companion object {
        fun of(page: Long, fraction: Float?): PageFraction? = fraction?.takeIf { it.isFinite() }?.let { PageFraction(page, it.coerceIn(0f, 1f)) }
    }
}

data class FollowedSeries(
    val series: Series,
    val unreadCount: Int,
    val followedAtEpochMs: Long? = null,
    val lastReadAtEpochMs: Long? = null,
    val lastUpdatedAtEpochMs: Long? = null,
    val started: Boolean = false,
    val entryCount: Int = 0,
)

data class EntryProgressRestore(
    val entries: List<StoredSeriesEntry> = emptyList(),
    val restoreLastOpened: Boolean = false,
    val lastOpenedEntry: String? = null,
) {
    val isEmpty: Boolean get() = entries.isEmpty() && !restoreLastOpened
}

interface SeriesRepository {
    fun observeFollowed(): Flow<List<FollowedSeries>>

    fun observeHasFollowed(): Flow<Boolean> = observeFollowed().map { it.isNotEmpty() }.distinctUntilChanged()

    fun observeSeries(id: SeriesId): Flow<StoredSeries?>

    fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>>

    suspend fun series(id: SeriesId): StoredSeries?

    suspend fun entries(id: SeriesId): List<StoredSeriesEntry>

    suspend fun listedEntry(id: SeriesId, address: String): StoredSeriesEntry? = entries(id).firstOrNull { it.entry.address == address }

    suspend fun storedEntry(id: SeriesId, address: String): StoredSeriesEntry? = listedEntry(id, address)

    suspend fun store(series: Series, entries: List<SeriesEntry>? = null): List<SeriesEntry>

    suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>, seriesMemo: String? = null): List<SeriesEntry>

    suspend fun setFollowed(id: SeriesId, followed: Boolean)

    suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode)

    suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean)

    suspend fun setChaptersShareFirstPage(id: SeriesId)

    suspend fun setAllRead(id: SeriesId, read: Boolean): EntryProgressRestore

    suspend fun setEntriesRead(id: SeriesId, entryAddresses: List<String>, read: Boolean): EntryProgressRestore

    suspend fun restoreProgress(id: SeriesId, restore: EntryProgressRestore)

    suspend fun recordProgress(id: SeriesId, entryAddress: String, position: Long, length: Long?, pageOffset: Float? = null)

    suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>): Boolean
}

fun StoredSeriesEntry.withProgress(position: Long, length: Long, kind: SeriesKind, pageOffset: Float? = null): StoredSeriesEntry = when (kind) {
    SeriesKind.MANGA -> {
        val reached = position.coerceIn(1, length.coerceAtLeast(1))
        val fraction = PageFraction.of(reached, pageOffset?.takeIf { it.isFinite() } ?: this.pageOffset.takeIf { reached == this.position })
        copy(position = reached, length = length, read = read || reached >= length, pageFraction = fraction)
    }
    SeriesKind.ANIME -> if (length <= 0) this else {
        val reached = position.coerceIn(0, length)
        copy(position = reached, length = length, read = read || reached * 100 >= length * ANIME_FINISHED_PERCENT)
    }
}

const val ANIME_FINISHED_PERCENT = 85

fun List<StoredSeriesEntry>.inReadingOrder(): List<StoredSeriesEntry> = sortedByDescending { it.sourceOrder }

fun continueEntry(series: StoredSeries, entries: List<StoredSeriesEntry>): StoredSeriesEntry? {
    val ordered = entries.inReadingOrder()
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

fun StoredSeriesEntry.resumePageIndex(): Int = if (read) 0 else (position - 1).coerceAtLeast(0).toInt()

fun StoredSeriesEntry.resumePageOffset(): Float? = if (read) null else pageOffset

fun StoredSeriesEntry.resumePositionMs(): Long {
    val length = length ?: return position
    return if (episodeStartsOver(position, length)) 0 else position
}

fun episodeStartsOver(positionMs: Long, lengthMs: Long): Boolean =
    lengthMs > 0 && positionMs >= lengthMs - minOf(RESUME_TAIL_MS, lengthMs / 10)

const val RESUME_TAIL_MS = 120_000L
