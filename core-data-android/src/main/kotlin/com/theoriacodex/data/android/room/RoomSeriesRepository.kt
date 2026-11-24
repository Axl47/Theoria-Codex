package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.theoriacodex.data.repository.EntryProgressRestore
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.NoChaptersToMoveToException
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.data.repository.carryProgress
import com.theoriacodex.data.repository.withProgress
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.seriesKind
import com.theoriacodex.domain.series.SeriesStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The `source_order` of an entry its source stopped listing. The row stays, hidden from every list and count, so the
 * chapter is neither new nor unread again if the source lists it once more.
 */
internal const val NOT_LISTED = -1

class RoomSeriesRepository(
    private val database: TheoriaRoomDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : SeriesRepository {
    private val dao = database.seriesDao()

    override fun observeFollowed(): Flow<List<FollowedSeries>> = dao.observeFollowed()
        .map { rows ->
            rows.map { row ->
                FollowedSeries(
                    series = row.series.toStored().series,
                    unreadCount = row.unreadCount,
                    followedAtEpochMs = row.series.followedAtEpochMs,
                    lastReadAtEpochMs = row.lastReadAtEpochMs,
                    lastUpdatedAtEpochMs = row.lastUpdatedAtEpochMs,
                    started = row.started,
                )
            }
        }
        .distinctUntilChanged().flowOn(Dispatchers.Default)

    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = dao.observeSeries(id.source.id, id.address)
        // Room emits null while no row matches, whatever the Java signature says.
        .map<SeriesEntity?, StoredSeries?> { it?.toStored() }
        .distinctUntilChanged().flowOn(Dispatchers.Default)

    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = dao.observeEntries(id.source.id, id.address)
        .map { rows -> rows.map(SeriesEntryEntity::toStored) }
        .distinctUntilChanged().flowOn(Dispatchers.Default)

    override suspend fun series(id: SeriesId): StoredSeries? =
        withContext(Dispatchers.IO) { dao.series(id.source.id, id.address) }?.toStored()

    override suspend fun entries(id: SeriesId): List<StoredSeriesEntry> =
        withContext(Dispatchers.IO) { dao.entries(id.source.id, id.address) }.map(SeriesEntryEntity::toStored)

    override suspend fun store(series: Series, entries: List<SeriesEntry>?): List<SeriesEntry> =
        database.withTransaction { write(series, entries) }

    /** [store]'s work. Must run in a transaction. */
    private fun write(series: Series, entries: List<SeriesEntry>?): List<SeriesEntry> {
        val id = series.id
        val before = dao.series(id.source.id, id.address)?.toStored() ?: StoredSeries(series)
        val stored = before.copy(series = series.keeping(before.series))
        if (!entries.isNullOrEmpty()) return storeEntries(stored, entries)
        dao.upsertSeries(stored.toEntity())
        return emptyList()
    }

    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>, seriesMemo: String?): List<SeriesEntry> =
        database.withTransaction {
            val stored = dao.series(id.source.id, id.address)?.toStored()
            if (stored == null || entries.isEmpty()) {
                emptyList()
            } else {
                storeEntries(stored.copy(series = stored.series.copy(memo = seriesMemo ?: stored.series.memo)), entries)
            }
        }

    /**
     * Writes [stored] as checked now, with [entries] as its entry list, and returns the new ones (none for its first
     * list). Must run in a transaction.
     */
    private fun storeEntries(stored: StoredSeries, entries: List<SeriesEntry>): List<SeriesEntry> {
        val id = stored.series.id
        val now = clock()
        // A source can list one address twice; the first is kept.
        val current = entries.distinctBy { it.address }
        val listed = current.mapTo(HashSet()) { it.address }
        val known = dao.allEntries(id.source.id, id.address).map(SeriesEntryEntity::toStored).associateBy { it.entry.address }
        val moved = movedEntries(current.partition { it.address in known }, known.values.filter { it.entry.address !in listed })
        val newEntries = mutableListOf<SeriesEntry>()
        val rows = current.mapIndexed { order, entry ->
            val kept = known[entry.address]
            val before = kept ?: moved[entry.address]
            if (before == null) newEntries += entry
            // An entry listed without a memo keeps the one stored at its address, as a series does.
            val listed = if (entry.memo == null && kept?.entry?.memo != null) entry.copy(memo = kept.entry.memo) else entry
            (before?.copy(entry = listed, sourceOrder = order) ?: StoredSeriesEntry(listed, order, firstSeenAtEpochMs = now))
        }
        // A dropped entry keeps its row and progress, also when a new address took its progress over: if the source
        // lists both again, neither is new or unread.
        val dropped = known.values.filter { it.entry.address !in listed && it.sourceOrder != NOT_LISTED }.map { it.copy(sourceOrder = NOT_LISTED) }
        val reopened = moved.entries.firstOrNull { it.value.entry.address == stored.lastOpenedEntry }?.key
        // The series first: its entries refer to its row.
        dao.upsertSeries(stored.copy(lastOpenedEntry = reopened ?: stored.lastOpenedEntry, lastCheckedAtEpochMs = now).toEntity())
        dao.upsertEntries((rows + dropped).map { it.toEntity(id) })
        return if (stored.lastCheckedAtEpochMs == null) emptyList() else newEntries
    }

    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = update(id) {
        if (it.followed == followed) it else it.copy(followed = followed, followedAtEpochMs = if (followed) clock() else null)
    }

    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = update(id) { it.copy(readingMode = mode) }

    override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = update(id) { it.copy(downloadNewChapters = on) }

    override suspend fun setChaptersShareFirstPage(id: SeriesId) = update(id) { it.copy(chaptersShareFirstPage = true) }

    override suspend fun setAllRead(id: SeriesId, read: Boolean): EntryProgressRestore = database.withTransaction {
        val current = dao.entries(id.source.id, id.address).map(SeriesEntryEntity::toStored)
        val changedBefore = current.filter { it.withReadState(null, read) != null }
        val seriesBefore = dao.series(id.source.id, id.address)?.toStored()
        val clearLastOpened = !read && seriesBefore?.lastOpenedEntry != null
        if (read) {
            if (changedBefore.isNotEmpty()) dao.markAllRead(id.source.id, id.address)
        } else {
            if (changedBefore.isNotEmpty()) dao.markAllUnread(id.source.id, id.address)
            if (clearLastOpened) dao.clearLastOpenedEntry(id.source.id, id.address)
        }
        EntryProgressRestore(changedBefore, restoreLastOpened = clearLastOpened, lastOpenedEntry = seriesBefore?.lastOpenedEntry)
    }

    override suspend fun setEntriesRead(id: SeriesId, entryAddresses: List<String>, read: Boolean): EntryProgressRestore {
        if (entryAddresses.isEmpty()) return EntryProgressRestore()
        val targets = entryAddresses.toHashSet()
        return database.withTransaction {
            val current = dao.entries(id.source.id, id.address).map(SeriesEntryEntity::toStored)
            val changedPairs = current.mapNotNull { before -> before.withReadState(targets, read)?.let { before to it } }
            if (changedPairs.isNotEmpty()) dao.upsertEntries(changedPairs.map { (_, after) -> after.toEntity(id) })
            val seriesBefore = dao.series(id.source.id, id.address)?.toStored()
            val clearLastOpened = !read && seriesBefore?.lastOpenedEntry in targets
            if (clearLastOpened) dao.clearLastOpenedEntry(id.source.id, id.address)
            EntryProgressRestore(
                entries = changedPairs.map { (before, _) -> before },
                restoreLastOpened = clearLastOpened,
                lastOpenedEntry = seriesBefore?.lastOpenedEntry,
            )
        }
    }

    override suspend fun restoreProgress(id: SeriesId, restore: EntryProgressRestore) {
        if (restore.isEmpty) return
        val byAddress = restore.entries.associateBy { it.entry.address }
        database.withTransaction {
            val restored = dao.entries(id.source.id, id.address)
                .map(SeriesEntryEntity::toStored)
                .mapNotNull { current ->
                    val target = byAddress[current.entry.address] ?: return@mapNotNull null
                    val next = current.copy(read = target.read, position = target.position, length = target.length)
                    next.takeIf { it != current }?.toEntity(id)
                }
            if (restored.isNotEmpty()) dao.upsertEntries(restored)
            if (restore.restoreLastOpened && restore.lastOpenedEntry != null) {
                val series = dao.series(id.source.id, id.address)?.toStored()
                if (series != null && series.lastOpenedEntry == null) {
                    dao.upsertSeries(series.copy(lastOpenedEntry = restore.lastOpenedEntry).toEntity())
                }
            }
        }
    }

    override suspend fun recordProgress(id: SeriesId, entryAddress: String, position: Long, length: Long?) {
        // A series moved to another source while its chapter was open is gone with its entries, so this writes nothing.
        database.withTransaction {
            val entry = dao.entry(id.source.id, id.address, entryAddress)?.toStored() ?: return@withTransaction
            val series = dao.series(id.source.id, id.address)?.toStored() ?: return@withTransaction
            // Unchanged rows are not written, so observers are not woken by a chapter reopened where it was left. The entry
            // takes the read time whenever it is written: when it is opened, or its place moves.
            val opened = series.lastOpenedEntry != entryAddress
            if (opened) dao.upsertSeries(series.copy(lastOpenedEntry = entryAddress).toEntity())
            val progressed = if (length == null || length <= 0) entry else entry.withProgress(position, length, id.source.seriesKind)
            if (opened || progressed != entry) dao.upsertEntries(listOf(progressed.copy(lastReadAtEpochMs = clock()).toEntity(id)))
        }
    }

    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>): Boolean {
        if (from == to.id) return false
        return database.withTransaction {
            val old = dao.series(from.source.id, from.address)?.toStored() ?: return@withTransaction false
            val oldEntries = dao.allEntries(from.source.id, from.address).map(SeriesEntryEntity::toStored)
            // All stored entries count, also ones the old source no longer lists: their progress goes with the series.
            if (entries.isEmpty() && oldEntries.any { it.read || it.position > 0 }) throw NoChaptersToMoveToException()
            write(to, entries)
            val target = dao.series(to.id.source.id, to.id.address).toStored()
            val (moved, changed) = carryProgress(old, oldEntries, target, dao.entries(to.id.source.id, to.id.address).map(SeriesEntryEntity::toStored))
            if (moved != target) dao.upsertSeries(moved.toEntity())
            dao.upsertEntries(changed.map { it.toEntity(to.id) })
            dao.deleteSeries(from.source.id, from.address)
            true
        }
    }

    private suspend fun update(id: SeriesId, change: (StoredSeries) -> StoredSeries) {
        database.withTransaction {
            val stored = dao.series(id.source.id, id.address)?.toStored() ?: return@withTransaction
            val changed = change(stored)
            if (changed != stored) dao.upsertSeries(changed.toEntity())
        }
    }
}

/** A chapter number and who released it: several groups release the same number, each a chapter of its own. */
private val SeriesEntry.release: Pair<Float, String?>? get() = number?.let { it to translatorGroup }

private fun StoredSeriesEntry.withReadState(targets: Set<String>?, read: Boolean): StoredSeriesEntry? {
    if (targets != null && entry.address !in targets) return null
    val next = if (read) copy(read = true) else copy(read = false, position = 0)
    return next.takeIf { it != this }
}

/**
 * The stored entry each added address continues, by that address. [listed] is the source's list now, split into the
 * addresses stored before and the added ones. An added entry continues a [dropped] one when each is the only one of
 * its [release] among its kind, and no address stored before is that release now, by what the source says of it now.
 * Anything less certain could hand one group's read chapter to another group's release, or hide a new chapter.
 */
private fun movedEntries(listed: Pair<List<SeriesEntry>, List<SeriesEntry>>, dropped: List<StoredSeriesEntry>): Map<String, StoredSeriesEntry> {
    val (kept, added) = listed
    val taken = kept.mapNotNullTo(HashSet()) { it.release }
    val droppedBy = dropped.groupBy { it.entry.release }
    return added.groupBy { it.release }.mapNotNull { (release, entries) ->
        val before = droppedBy[release]?.singleOrNull()
        if (release == null || release in taken || before == null) null else entries.singleOrNull()?.let { it.address to before }
    }.toMap()
}

internal fun SeriesEntity.toStored() = StoredSeries(
    series = Series(
        id = SeriesId(SourceKey.parseOrThrow(source), address),
        title = title,
        coverUrl = coverUrl,
        author = author,
        genres = genres.lines().filter(String::isNotBlank),
        language = language,
        description = description,
        status = SeriesStatus.entries.firstOrNull { it.name == status } ?: SeriesStatus.UNKNOWN,
        memo = memo,
    ),
    followed = followed,
    readingMode = readingMode?.let { name -> GalleryReadingMode.entries.firstOrNull { it.name == name } },
    lastOpenedEntry = lastOpenedEntry,
    lastCheckedAtEpochMs = lastCheckedAtEpochMs,
    downloadNewChapters = downloadNew,
    chaptersShareFirstPage = chaptersShareFirstPage,
    followedAtEpochMs = followedAtEpochMs,
)

internal fun StoredSeries.toEntity() = SeriesEntity(
    series.id.source.id,
    series.id.address,
    series.title,
    series.coverUrl,
    series.author,
    series.genres.joinToString("\n"),
    series.language,
    followed,
    readingMode?.name,
    lastOpenedEntry,
    lastCheckedAtEpochMs,
    series.description,
    series.status.takeUnless { it == SeriesStatus.UNKNOWN }?.name,
    downloadNewChapters,
    chaptersShareFirstPage,
    followedAtEpochMs,
    series.memo,
)

/**
 * [this] with [stored]'s description, status and memo where it has none: only a details page gives the first two, so a
 * listing of the series, or details that leave them out, must not erase what an earlier details page said. A source
 * may likewise give its memo on one of those and not the other, and needs it to open the series again.
 */
private fun Series.keeping(stored: Series): Series = copy(
    description = description ?: stored.description,
    status = status.takeUnless { it == SeriesStatus.UNKNOWN } ?: stored.status,
    memo = memo ?: stored.memo,
)

internal fun SeriesEntryEntity.toStored() = StoredSeriesEntry(
    entry = SeriesEntry(address, name, number, translatorGroup, uploadedAtEpochMs, memo),
    sourceOrder = sourceOrder,
    firstSeenAtEpochMs = firstSeenAtEpochMs,
    read = read,
    position = lastPage,
    length = pageCount,
    lastReadAtEpochMs = lastReadAtEpochMs,
)

internal fun StoredSeriesEntry.toEntity(series: SeriesId) = SeriesEntryEntity(
    series.source.id,
    series.address,
    entry.address,
    entry.name,
    entry.number,
    entry.translatorGroup,
    entry.uploadedAtEpochMs,
    sourceOrder,
    firstSeenAtEpochMs,
    read,
    position,
    length,
    lastReadAtEpochMs,
    entry.memo,
)
