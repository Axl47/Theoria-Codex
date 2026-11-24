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

    override fun observeHasFollowed(): Flow<Boolean> = dao.observeHasFollowed().distinctUntilChanged()

    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = dao.observeSeries(id.source.id, id.address)
        .map<SeriesEntity?, StoredSeries?> { it?.toStored() }
        .distinctUntilChanged().flowOn(Dispatchers.Default)

    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = dao.observeEntries(id.source.id, id.address)
        .map { rows -> rows.map(SeriesEntryEntity::toStored) }
        .distinctUntilChanged().flowOn(Dispatchers.Default)

    override suspend fun series(id: SeriesId): StoredSeries? =
        withContext(Dispatchers.IO) { dao.series(id.source.id, id.address) }?.toStored()

    override suspend fun entries(id: SeriesId): List<StoredSeriesEntry> =
        withContext(Dispatchers.IO) { dao.entries(id.source.id, id.address) }.map(SeriesEntryEntity::toStored)

    override suspend fun listedEntry(id: SeriesId, address: String): StoredSeriesEntry? =
        withContext(Dispatchers.IO) { dao.listedEntry(id.source.id, id.address, address) }?.toStored()

    override suspend fun storedEntry(id: SeriesId, address: String): StoredSeriesEntry? =
        withContext(Dispatchers.IO) { dao.entry(id.source.id, id.address, address) }?.toStored()

    override suspend fun store(series: Series, entries: List<SeriesEntry>?): List<SeriesEntry> =
        database.withTransaction { write(series, entries) }

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
            val stored = dao.series(id.source.id, id.address)?.toStored() ?: return@withTransaction emptyList()
            val withMemo = stored.copy(series = stored.series.copy(memo = seriesMemo ?: stored.series.memo))
            when {
                entries.isNotEmpty() -> storeEntries(withMemo, entries)
                withMemo != stored -> emptyList<SeriesEntry>().also { dao.upsertSeries(withMemo.toEntity()) }
                else -> emptyList()
            }
        }

    private fun storeEntries(stored: StoredSeries, entries: List<SeriesEntry>): List<SeriesEntry> {
        val id = stored.series.id
        val now = clock()
        val current = entries.distinctBy { it.address }
        val listed = current.mapTo(HashSet()) { it.address }
        val known = dao.allEntries(id.source.id, id.address).map(SeriesEntryEntity::toStored).associateBy { it.entry.address }
        val moved = movedEntries(current.partition { it.address in known }, known.values.filter { it.entry.address !in listed })
        val newEntries = mutableListOf<SeriesEntry>()
        val rows = current.mapIndexed { order, entry ->
            val kept = known[entry.address]
            val before = kept ?: moved[entry.address]
            if (before == null) newEntries += entry
            val listed = if (entry.memo == null && kept?.entry?.memo != null) entry.copy(memo = kept.entry.memo) else entry
            (before?.copy(entry = listed, sourceOrder = order) ?: StoredSeriesEntry(listed, order, firstSeenAtEpochMs = now))
        }
        val dropped = known.values.filter { it.entry.address !in listed && it.sourceOrder != NOT_LISTED }.map { it.copy(sourceOrder = NOT_LISTED) }
        val reopened = moved.entries.firstOrNull { it.value.entry.address == stored.lastOpenedEntry }?.key
        dao.upsertSeries(stored.copy(lastOpenedEntry = reopened ?: stored.lastOpenedEntry, checkedAtEpochMs = now).toEntity())
        dao.upsertEntries((rows + dropped).map { it.toEntity(id) })
        return when {
            newEntries.size != current.size -> newEntries
            known.isEmpty() -> datedAfterFollowing(stored, newEntries)
            else -> emptyList()
        }
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
        database.withTransaction {
            val entry = dao.entry(id.source.id, id.address, entryAddress)?.toStored() ?: return@withTransaction
            val series = dao.series(id.source.id, id.address)?.toStored() ?: return@withTransaction
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
            if (entries.isEmpty() && oldEntries.any(StoredSeriesEntry::hasProgress)) throw NoChaptersToMoveToException()
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

private val SeriesEntry.release: Pair<Float, String?>? get() = number?.let { it to translatorGroup }

private fun StoredSeriesEntry.withReadState(targets: Set<String>?, read: Boolean): StoredSeriesEntry? {
    if (targets != null && entry.address !in targets) return null
    val next = if (read) copy(read = true) else copy(read = false, position = 0)
    return next.takeIf { it != this }
}

private fun movedEntries(listed: Pair<List<SeriesEntry>, List<SeriesEntry>>, dropped: List<StoredSeriesEntry>): Map<String, StoredSeriesEntry> {
    val (kept, added) = listed
    val taken = kept.mapNotNullTo(HashSet()) { it.release }
    val droppedBy = dropped.groupBy { it.entry.release }
    return added.groupBy { it.release }.mapNotNull { (release, entries) ->
        val before = droppedBy[release]?.singleOrNull()
        if (release == null || release in taken || before == null) null else entries.singleOrNull()?.let { it.address to before }
    }.toMap()
}

private fun datedAfterFollowing(stored: StoredSeries, entries: List<SeriesEntry>): List<SeriesEntry> {
    val since = (stored.followedAtEpochMs ?: return emptyList()) - DATE_SLACK_MS
    return entries.filter { entry -> entry.uploadedAtEpochMs?.let { it > since } == true }
}

private const val DATE_SLACK_MS = 24 * 60 * 60 * 1_000L

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
    downloadNewChapters = downloadNew,
    chaptersShareFirstPage = chaptersShareFirstPage,
    followedAtEpochMs = followedAtEpochMs,
    checkedAtEpochMs = checkedAtEpochMs,
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
    series.description,
    series.status.takeUnless { it == SeriesStatus.UNKNOWN }?.name,
    downloadNewChapters,
    chaptersShareFirstPage,
    followedAtEpochMs,
    series.memo,
    checkedAtEpochMs,
)

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
