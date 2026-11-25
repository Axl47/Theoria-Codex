package com.theoriacodex.app.series

import com.theoriacodex.app.di.SeriesDependencies
import com.theoriacodex.data.repository.DownloadScheduler
import com.theoriacodex.data.repository.EntryProgressRestore
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
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.NewExtensionRepo
import com.theoriacodex.domain.series.RepoListing
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.ExtensionSourceSettings
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesCovers
import com.theoriacodex.domain.series.SeriesEntries
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

internal class FakeSeriesSource(override val key: SourceKey, private val answer: (asked: Int) -> EntryMedia) : SeriesSource {
    var asked = 0
        private set
    override val name = "Fake"
    override val language = "en"
    override suspend fun media(entry: SeriesEntry) = answer(++asked)
    override suspend fun popular(page: Int): SeriesPage = error("unused")
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>): SeriesPage = error("unused")
    override suspend fun details(series: Series): Series = series
    override suspend fun entries(series: Series, stored: List<SeriesEntry>) = SeriesEntries(emptyList())
}

internal class StoredOnly(stored: StoredSeries, private var entries: List<StoredSeriesEntry>) : SeriesRepository {
    private val stored = MutableStateFlow(stored)
    val progress = mutableListOf<String>()
    override suspend fun series(id: SeriesId) = stored.value.takeIf { it.series.id == id }
    override suspend fun entries(id: SeriesId) = entries
    override fun observeFollowed(): Flow<List<FollowedSeries>> = emptyFlow()
    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = stored.map { it.takeIf { it.series.id == id } }
    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = emptyFlow()
    override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>, seriesMemo: String?) = emptyList<SeriesEntry>()
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
    override suspend fun setChaptersShareFirstPage(id: SeriesId) = stored.update { it.copy(chaptersShareFirstPage = true) }
    override suspend fun setAllRead(id: SeriesId, read: Boolean) = EntryProgressRestore()
    override suspend fun setEntriesRead(id: SeriesId, entryAddresses: List<String>, read: Boolean) = EntryProgressRestore()
    override suspend fun restoreProgress(id: SeriesId, restore: EntryProgressRestore) = Unit
    override suspend fun recordProgress(id: SeriesId, entryAddress: String, position: Long, length: Long?, pageOffset: Float?) {
        progress += "$entryAddress $position/$length"
        stored.update { it.copy(lastOpenedEntry = entryAddress) }
        if (length == null || length <= 0) return
        entries = entries.map { if (it.entry.address == entryAddress) it.withProgress(position, length, id.source.seriesKind, pageOffset) else it }
    }
}

internal object NoExtensions : SeriesExtensions {
    override val revision = MutableStateFlow(0)

    override suspend fun installed(): List<InstalledExtension> = emptyList()

    override suspend fun setOn(id: String, signer: String, on: Boolean) = installed()

    override suspend fun setLanguage(id: String, language: String, on: Boolean) = installed()

    override suspend fun settings(id: String) = emptyList<ExtensionSourceSettings>()

    override suspend fun change(id: String, sourceId: String, setting: ExtensionSetting) = settings(id)
}

internal object NoRepos : ExtensionRepos {
    override suspend fun repos() = emptyList<ExtensionRepo>()

    override suspend fun add(repos: List<NewExtensionRepo>) = repos.associate { it.url to "Offline" }

    override suspend fun restore(repos: List<ExtensionRepo>) = repos.associate { it.url to "Offline" }

    override suspend fun remove(url: String) = Unit

    override suspend fun available(refresh: Boolean) = RepoListing(emptyList())

    override suspend fun install(repo: String, packageName: String) = "Offline"

    override suspend fun uninstall(id: String) = Unit
}

internal fun TestScope.seriesDependencies(
    folder: File,
    repository: SeriesRepository,
    sources: suspend () -> List<SeriesSource>,
    deleteAfterReading: Boolean = false,
    store: OfflineMediaStore = OfflineMediaStore(folder.resolve("downloads"), StandardTestDispatcher(testScheduler)),
    pictures: ChapterPictures = ChapterPictures(repository),
): SeriesDependencies {
    val covers = SeriesCovers { series -> series.coverUrl?.let { "$it#loadable" } }
    val source: suspend (SourceKey) -> SeriesSource? = { key -> sources().firstOrNull { it.key == key } }
    val downloads = SeriesDownloads(
        store = store,
        queueFile = folder.resolve("queue.json"),
        repository = repository,
        source = source,
        covers = covers,
        fetch = { _, _ -> error("Nothing downloads here") },
        scheduler = object : DownloadScheduler {
            override suspend fun schedule(automatic: Boolean) = Unit

            override suspend fun ensureScheduled(automatic: Boolean) = Unit
        },
        deleteAfterReading = { deleteAfterReading },
        files = AtomicJsonFileStore(StandardTestDispatcher(testScheduler)),
    )
    return SeriesDependencies(repository, sources, source, NoExtensions, NoRepos, downloads, scope = this, covers = covers, pictures = pictures, log = {})
}
