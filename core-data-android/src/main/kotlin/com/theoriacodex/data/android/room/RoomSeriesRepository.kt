package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.data.repository.withProgress
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
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
private const val NOT_LISTED = -1

class RoomSeriesRepository(
    private val database: TheoriaRoomDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : SeriesRepository {
    private val dao = database.seriesDao()

    override fun observeFollowed(): Flow<List<FollowedSeries>> = dao.observeFollowed()
        .map { rows -> rows.map { row -> FollowedSeries(row.series.toStored().series, row.unreadCount) } }
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

    override suspend fun store(series: Series, entries: List<SeriesEntry>?): List<SeriesEntry> {
        val id = series.id
        return database.withTransaction {
            val before = dao.series(id.source.id, id.address)?.toStored() ?: StoredSeries(series)
            val stored = before.copy(series = series.keeping(before.series))
            if (!entries.isNullOrEmpty()) return@withTransaction storeEntries(stored, entries)
            dao.upsertSeries(stored.toEntity())
            emptyList()
        }
    }

    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>): List<SeriesEntry> = database.withTransaction {
        val stored = dao.series(id.source.id, id.address)?.toStored()
        if (stored == null || entries.isEmpty()) emptyList() else storeEntries(stored, entries)
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
        val moved = movedEntries(known.values.partition { it.entry.address in listed }, current.filter { it.address !in known })
        val newEntries = mutableListOf<SeriesEntry>()
        val rows = current.mapIndexed { order, entry ->
            val before = known[entry.address] ?: moved[entry.address]
            if (before == null) newEntries += entry
            (before?.copy(entry = entry, sourceOrder = order) ?: StoredSeriesEntry(entry, order, firstSeenAtEpochMs = now))
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

    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = update(id) { it.copy(followed = followed) }

    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = update(id) { it.copy(readingMode = mode) }

    override suspend fun recordProgress(id: SeriesId, entryAddress: String, page: Int, pageCount: Int?) {
        database.withTransaction {
            val entry = dao.entry(id.source.id, id.address, entryAddress)?.toStored() ?: return@withTransaction
            val series = dao.series(id.source.id, id.address)?.toStored() ?: return@withTransaction
            // Unchanged rows are not written, so observers are not woken on every page of a chapter already recorded.
            if (series.lastOpenedEntry != entryAddress) dao.upsertSeries(series.copy(lastOpenedEntry = entryAddress).toEntity())
            val progressed = if (pageCount == null || pageCount <= 0) entry else entry.withProgress(page, pageCount)
            if (progressed != entry) dao.upsertEntries(listOf(progressed.toEntity(id)))
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

/**
 * The stored entry each of the [added] addresses continues, by its address: a dropped entry (the second of [known],
 * which is split into listed and not) that is the only dropped one of its [release], for an added entry that is the
 * only added one of it, while no listed entry is that release already. Anything less certain could hand one group's
 * read chapter to another group's release, or hide a new chapter.
 */
private fun movedEntries(
    known: Pair<List<StoredSeriesEntry>, List<StoredSeriesEntry>>,
    added: List<SeriesEntry>,
): Map<String, StoredSeriesEntry> {
    val (listed, dropped) = known
    val taken = listed.mapNotNullTo(HashSet()) { it.entry.release }
    val droppedBy = dropped.groupBy { it.entry.release }
    return added.groupBy { it.release }.mapNotNull { (release, entries) ->
        val before = droppedBy[release]?.singleOrNull()
        if (release == null || release in taken || before == null) null else entries.singleOrNull()?.let { it.address to before }
    }.toMap()
}

private fun SeriesEntity.toStored() = StoredSeries(
    series = Series(
        id = SeriesId(SourceKey.parseOrThrow(source), address),
        title = title,
        coverUrl = coverUrl,
        author = author,
        genres = genres.lines().filter(String::isNotBlank),
        language = language,
        description = description,
        status = SeriesStatus.entries.firstOrNull { it.name == status } ?: SeriesStatus.UNKNOWN,
    ),
    followed = followed,
    readingMode = readingMode?.let { name -> GalleryReadingMode.entries.firstOrNull { it.name == name } },
    lastOpenedEntry = lastOpenedEntry,
    lastCheckedAtEpochMs = lastCheckedAtEpochMs,
)

private fun StoredSeries.toEntity() = SeriesEntity(
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
)

/**
 * [this] with [stored]'s description and status where it has none: only a details page gives them, so a listing of
 * the series, or details that leave them out, must not erase what an earlier details page said.
 */
private fun Series.keeping(stored: Series): Series = copy(
    description = description ?: stored.description,
    status = status.takeUnless { it == SeriesStatus.UNKNOWN } ?: stored.status,
)

private fun SeriesEntryEntity.toStored() = StoredSeriesEntry(
    entry = SeriesEntry(address, name, number, translatorGroup, uploadedAtEpochMs),
    sourceOrder = sourceOrder,
    firstSeenAtEpochMs = firstSeenAtEpochMs,
    read = read,
    lastPage = lastPage,
    pageCount = pageCount,
)

private fun StoredSeriesEntry.toEntity(series: SeriesId) = SeriesEntryEntity(
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
    lastPage,
    pageCount,
)
