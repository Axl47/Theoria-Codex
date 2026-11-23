package com.theoriacodex.app.series

import com.theoriacodex.app.di.SeriesDependencies
import com.theoriacodex.data.repository.DownloadScheduler
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.data.repository.SeriesDownloads
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.data.repository.withProgress
import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.ExtensionSourceSettings
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.seriesKind
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/** A source whose every entry opens as [answer] gives for the [asked]th ask; its kind comes from [key]. */
internal class FakeSeriesSource(override val key: SourceKey, private val answer: (asked: Int) -> EntryMedia) : SeriesSource {
    var asked = 0
        private set
    override val name = "Fake"
    override val language = "en"
    override suspend fun media(entry: SeriesEntry) = answer(++asked)
    override suspend fun popular(page: Int): SeriesPage = error("unused")
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>): SeriesPage = error("unused")
    override suspend fun details(series: Series): Series = series
    override suspend fun entries(series: Series): List<SeriesEntry> = emptyList()
}

/**
 * A repository over one stored series that records progress as the Room repository does: the entry opened last, and
 * the progress by [withProgress] once the length is known. [progress] lists each call as "address position/length".
 */
internal class StoredOnly(private var stored: StoredSeries, private var entries: List<StoredSeriesEntry>) : SeriesRepository {
    val progress = mutableListOf<String>()
    override suspend fun series(id: SeriesId) = stored.takeIf { it.series.id == id }
    override suspend fun entries(id: SeriesId) = entries
    override fun observeFollowed(): Flow<List<FollowedSeries>> = emptyFlow()
    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = emptyFlow()
    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = emptyFlow()
    override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>) = emptyList<SeriesEntry>()
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
    override suspend fun recordProgress(id: SeriesId, entryAddress: String, position: Long, length: Long?) {
        progress += "$entryAddress $position/$length"
        stored = stored.copy(lastOpenedEntry = entryAddress)
        if (length == null || length <= 0) return
        entries = entries.map { if (it.entry.address == entryAddress) it.withProgress(position, length, id.source.seriesKind) else it }
    }
}

/** The reader never asks about extensions. */
internal object NoExtensions : SeriesExtensions {
    override val revision = MutableStateFlow(0)

    override suspend fun installed(): List<InstalledExtension> = emptyList()

    override suspend fun setOn(id: String, signer: String, on: Boolean) = installed()

    override suspend fun setLanguage(id: String, language: String, on: Boolean) = installed()

    override suspend fun settings(id: String) = emptyList<ExtensionSourceSettings>()

    override suspend fun change(id: String, sourceId: String, setting: ExtensionSetting) = settings(id)
}

/** A series reader's dependencies over [repository], with downloads in [folder] that never fetch. */
internal fun TestScope.seriesDependencies(
    folder: File,
    repository: SeriesRepository,
    sources: suspend () -> List<SeriesSource>,
    deleteAfterReading: Boolean = false,
    store: OfflineMediaStore = OfflineMediaStore(folder.resolve("downloads"), StandardTestDispatcher(testScheduler)),
): SeriesDependencies {
    val downloads = SeriesDownloads(
        store = store,
        queueFile = folder.resolve("queue.json"),
        repository = repository,
        sources = sources,
        fetch = { _, _ -> error("Nothing downloads here") },
        scheduler = object : DownloadScheduler {
            override suspend fun schedule(automatic: Boolean) = Unit

            override suspend fun ensureScheduled(automatic: Boolean) = Unit
        },
        deleteAfterReading = { deleteAfterReading },
        files = AtomicJsonFileStore(StandardTestDispatcher(testScheduler)),
    )
    return SeriesDependencies(repository, sources, NoExtensions, downloads, scope = this)
}
