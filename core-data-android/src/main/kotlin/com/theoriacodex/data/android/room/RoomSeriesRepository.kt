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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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
            val stored = dao.series(id.source.id, id.address)?.toStored() ?: StoredSeries(series)
            val listed = !entries.isNullOrEmpty()
            dao.upsertSeries(
                stored.copy(series = series, lastCheckedAtEpochMs = if (listed) clock() else stored.lastCheckedAtEpochMs).toEntity(),
            )
            if (entries.isNullOrEmpty()) return@withTransaction emptyList()
            val known = dao.entries(id.source.id, id.address).associateBy { it.address }
            val now = clock()
            // A source can list one address twice; the first is kept.
            val current = entries.distinctBy { it.address }
            dao.deleteEntriesExcept(id.source.id, id.address, current.map { it.address })
            dao.upsertEntries(
                current.mapIndexed { order, entry ->
                    val before = known[entry.address]?.toStored()
                    (before?.copy(entry = entry, sourceOrder = order) ?: StoredSeriesEntry(entry, order, firstSeenAtEpochMs = now))
                        .toEntity(id)
                },
            )
            if (stored.lastCheckedAtEpochMs == null) emptyList() else current.filter { it.address !in known }
        }
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

private fun SeriesEntity.toStored() = StoredSeries(
    series = Series(
        id = SeriesId(SourceKey.parseOrThrow(source), address),
        title = title,
        coverUrl = coverUrl,
        author = author,
        genres = genres.lines().filter(String::isNotBlank),
        language = language,
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
