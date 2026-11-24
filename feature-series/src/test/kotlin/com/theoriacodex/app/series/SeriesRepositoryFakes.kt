package com.theoriacodex.app.series

import com.theoriacodex.data.repository.EntryProgressRestore
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class Following(private val followed: Flow<List<FollowedSeries>>) : SeriesRepository by NothingStored {
    override fun observeFollowed() = followed
    override fun observeHasFollowed() = followed.map { it.isNotEmpty() }
}

internal object NothingStored : SeriesRepository {
    override fun observeFollowed(): Flow<List<FollowedSeries>> = flowOf(emptyList())
    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = emptyFlow()
    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = emptyFlow()
    override suspend fun series(id: SeriesId): StoredSeries? = null
    override suspend fun entries(id: SeriesId): List<StoredSeriesEntry> = emptyList()
    override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>, seriesMemo: String?) = emptyList<SeriesEntry>()
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
    override suspend fun setChaptersShareFirstPage(id: SeriesId) = Unit
    override suspend fun setAllRead(id: SeriesId, read: Boolean) = EntryProgressRestore()
    override suspend fun setEntriesRead(id: SeriesId, entryAddresses: List<String>, read: Boolean) = EntryProgressRestore()
    override suspend fun restoreProgress(id: SeriesId, restore: EntryProgressRestore) = Unit
    override suspend fun recordProgress(id: SeriesId, entryAddress: String, position: Long, length: Long?) = Unit
    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
}
