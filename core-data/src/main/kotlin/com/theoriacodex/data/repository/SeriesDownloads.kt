package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.domain.coroutines.runCatchingExceptionsPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.playable
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesCovers
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.seriesKind
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

sealed interface ChapterDownload {
    data class Queued(val progress: Float?) : ChapterDownload

    data object OnDevice : ChapterDownload
}

data class QueuedChapter(val series: SeriesId, val entry: String, val automatic: Boolean = false)

data class DrainProgress(val series: String, val chapter: String, val seriesId: SeriesId, val number: Int, val of: Int, val pages: Int, val pageCount: Int)

data class ChapterName(val seriesId: SeriesId, val series: String, val chapter: String)

data class DrainResult(
    val downloaded: Int,
    val failed: List<ChapterName> = emptyList(),
    val retry: Boolean = false,
    val waiting: List<QueuedChapter> = emptyList(),
)

class ChapterUnavailable(message: String) : IOException(message)

enum class DownloadChoice {
    NEXT_UNREAD,
    ALL_UNREAD,
    ALL,
}

interface DownloadScheduler {
    suspend fun schedule(automatic: Boolean)

    suspend fun ensureScheduled(automatic: Boolean)
}

fun seriesOfflineOwner(id: SeriesId): String = "$SERIES_OWNER_PREFIX${id.source.id}\n${id.address}"

fun seriesIdOfOfflineOwner(owner: String): SeriesId? {
    val pieces = owner.takeIf { it.startsWith(SERIES_OWNER_PREFIX) }?.removePrefix(SERIES_OWNER_PREFIX)?.split('\n', limit = 2)
    if (pieces == null || pieces.size != 2 || pieces[1].isEmpty()) return null
    return SourceKey.parse(pieces[0])?.let { SeriesId(it, pieces[1]) }
}

private const val SERIES_OWNER_PREFIX = "series:"

fun chapterPost(series: Series, entry: SeriesEntry, cover: String?, pages: List<ImageRef> = emptyList()) =
    entryPost(series, entry, cover, pages, mediaCount = pages.size.takeIf { it > 0 })

fun episodePost(series: Series, entry: SeriesEntry, cover: String?, streams: List<MediaStream> = emptyList()): Post {
    val playable = streams.playable()
    val video = playable.takeIf { it.isNotEmpty() }?.let { ImageRef(url = null, localPath = null, mime = null, streams = it) }
    return entryPost(series, entry, cover, listOfNotNull(video), mediaCount = 1)
}

private fun entryPost(series: Series, entry: SeriesEntry, cover: String?, media: List<ImageRef>, mediaCount: Int?) = Post(
    id = PostId(series.id.source, entry.address),
    preview = ImageRef(url = cover, localPath = null, mime = null),
    full = media.firstOrNull(),
    pageUrl = null,
    width = null,
    height = null,
    canonicalTags = emptyList(),
    rawTags = emptyList(),
    authorName = null,
    createdAtEpochMs = entry.uploadedAtEpochMs,
    title = "${series.title} · ${entry.name}",
    media = media,
    mediaCount = mediaCount,
    galleryLengthUnknown = media.isEmpty(),
)

class SeriesDownloads(
    private val store: OfflineMediaStore,
    private val queueFile: File,
    private val repository: SeriesRepository,
    private val source: suspend (SourceKey) -> SeriesSource?,
    private val covers: SeriesCovers,
    private val fetch: suspend (url: String, into: File) -> String,
    private val scheduler: DownloadScheduler,
    private val deleteAfterReading: suspend () -> Boolean,
    private val files: AtomicJsonFileStore = AtomicJsonFileStore(),
    private val retryDelay: Duration = 2.seconds,
) {
    private val lock = Mutex()

    private val drains = Mutex()
    private val queue = MutableStateFlow<List<QueuedChapter>>(emptyList())
    private val fetching = MutableStateFlow<Pair<QueuedChapter, Float>?>(null)
    private var loaded = false

    private val draining = mutableMapOf<Boolean, Any>()
    private var active: Pair<QueuedChapter, Job>? = null

    private var sourceChanges = 0

    fun states(id: SeriesId): Flow<Map<String, ChapterDownload>> = flow {
        lock.withLock { load() }
        val downloaded = store.scannedSnapshots.map { it.chapters(id) }.distinctUntilChanged()
        emitAll(
            combine(downloaded, queue, fetching) { downloaded, queued, current ->
                downloaded.associateWith<String, ChapterDownload> { ChapterDownload.OnDevice } +
                    queued.filter { it.series == id }.associate { chapter ->
                        chapter.entry to ChapterDownload.Queued(current?.takeIf { it.first == chapter }?.second)
                    }
            },
        )
    }

    suspend fun chapter(id: SeriesId, entry: String): Post? = store.find(PostId(id.source, entry))

    fun leadPages(id: SeriesId): Flow<Map<String, List<ImageRef>>> =
        store.scannedSnapshots.map { it.leadPages(id) }.distinctUntilChanged()

    suspend fun downloaded(id: SeriesId): Set<String> = store.load().chapters(id)

    fun downloadedCounts(): Flow<Map<SeriesId, Int>> = store.scannedSnapshots.map { snapshot ->
        snapshot.postsByOwner.mapNotNull { (owner, posts) -> seriesIdOfOfflineOwner(owner)?.takeIf { posts.isNotEmpty() }?.let { it to posts.size } }.toMap()
    }.distinctUntilChanged()

    suspend fun resume() {
        val queued = lock.withLock {
            load()
            queue.value
        }
        deleteRead()
        queued.map { it.automatic }.distinct().forEach { scheduler.ensureScheduled(it) }
    }

    suspend fun rescheduleOnChanges(sources: Flow<*>, network: Flow<*>): Unit = coroutineScope {
        launch {
            sources.distinctUntilChanged().drop(1).collect {
                lock.withLock { sourceChanges++ }
                reschedule(automatic = false, evenDraining = false)
                reschedule(automatic = true, evenDraining = false)
            }
        }
        launch { network.distinctUntilChanged().drop(1).collect { reschedule(automatic = false, evenDraining = true) } }
    }

    private suspend fun reschedule(automatic: Boolean, evenDraining: Boolean) {
        val schedule = lock.withLock {
            load()
            queue.value.any { it.automatic == automatic } && (evenDraining || automatic !in draining)
        }
        if (schedule) scheduler.schedule(automatic)
    }

    suspend fun deleteRead(noReaderOpen: () -> Boolean = { true }) {
        if (!deleteAfterReading()) return
        store.load().postsByOwner.forEach { (owner, posts) ->
            val id = seriesIdOfOfflineOwner(owner) ?: return@forEach
            val chapters = posts.mapTo(HashSet()) { it.sourcePostId }
            val series = repository.series(id) ?: return@forEach
            val read = repository.entries(id).filter { it.read }.mapTo(HashSet()) { it.entry.address }
            (chapters intersect read).minus(setOfNotNull(series.lastOpenedEntry)).forEach {
                if (!noReaderOpen()) return
                delete(id, it)
            }
        }
    }

    suspend fun download(id: SeriesId, entries: List<String>, automatic: Boolean = false): Unit = withContext(NonCancellable) {
        val onDevice = downloaded(id)
        val schedule = lock.withLock {
            load()
            val queued = queue.value.mapTo(HashSet()) { it.series to it.entry }
            val added = entries.distinct().filter { it !in onDevice && (id to it) !in queued }.map { QueuedChapter(id, it, automatic) }
            if (added.isEmpty()) return@withContext
            save(queue.value + added)
            automatic !in draining
        }
        if (schedule) scheduler.schedule(automatic)
    }

    suspend fun download(id: SeriesId, choice: DownloadChoice) {
        val series = repository.series(id) ?: return
        val present = downloaded(id) + lock.withLock { load(); queue.value.filter { it.series == id }.map { it.entry } }
        download(id, chaptersToDownload(choice, series, repository.entries(id), present))
    }

    suspend fun downloadNew(update: SeriesUpdate) {
        if (update.series.id.source.seriesKind != SeriesKind.MANGA) return
        runCatchingExceptionsPreservingCancellation {
            val series = repository.series(update.series.id) ?: return
            if (!series.followed || !series.downloadNewChapters) return
            download(update.series.id, update.newEntries.asReversed().map { it.address }, automatic = true)
        }.onFailure { error ->
            Diagnostics.report("series", "New chapters could not be queued for download", error)
        }
    }

    fun queuedLanes(): Flow<Set<Boolean>> = flow {
        lock.withLock { load() }
        emitAll(queue.map { chapters -> chapters.mapTo(HashSet()) { it.automatic } }.distinctUntilChanged())
    }

    suspend fun delete(id: SeriesId, entry: String) = withContext(NonCancellable) {
        remove { it.series == id && it.entry == entry }
        store.removeOwner(seriesOfflineOwner(id), PostId(id.source, entry))
    }

    suspend fun deleteSeries(id: SeriesId) = withContext(NonCancellable) {
        remove { it.series == id }
        store.removeOwner(seriesOfflineOwner(id))
    }

    suspend fun cancelAll() = withContext(NonCancellable) { remove { true } }

    suspend fun drain(automatic: Boolean, onProgress: (DrainProgress) -> Unit = {}): DrainResult {
        val token = Any()
        val held = lock.withLock {
            load()
            draining[automatic] = token
            Held(sourceChanges)
        }
        var downloaded = 0
        val failed = mutableListOf<ChapterName>()
        try {
            while (true) {
                val (chapter, outcome) = drains.withLock { fetchNext(automatic, token, held, downloaded + failed.size + 1, onProgress) } ?: break
                val source = chapter.seriesId.source
                when (outcome) {
                    Outcome.DOWNLOADED -> downloaded++
                    Outcome.UNAVAILABLE -> failed += chapter
                    Outcome.SOURCE_OFF -> {
                        held.sources += source
                        held.off += source
                    }
                    Outcome.LATER -> held.sources += source
                    Outcome.REMOVED -> Unit
                }
            }
            val waiting = lock.withLock { queue.value.filter { it.automatic == automatic && it.series.source in held.off } }
            return DrainResult(downloaded, failed, retry = (held.sources - held.off).isNotEmpty(), waiting = waiting)
        } finally {
            withContext(NonCancellable) { lock.withLock { draining.remove(automatic, token) } }
        }
    }

    private suspend fun fetchNext(
        automatic: Boolean,
        token: Any,
        held: Held,
        number: Int,
        onProgress: (DrainProgress) -> Unit,
    ): Pair<ChapterName, Outcome>? {
        val (next, remaining) = lock.withLock {
            if (held.changes != sourceChanges) held.releaseOff(sourceChanges)
            val lane = queue.value.filter { it.automatic == automatic && it.series.source !in held.sources }
            if (lane.isEmpty()) draining.remove(automatic, token)
            lane.firstOrNull() to lane.size
        }
        next ?: return null
        val name = names(next)
        val outcome = fetchQueued(next) { pages, of ->
            onProgress(DrainProgress(name.series, name.chapter, next.series, number, number + remaining - 1, pages, of))
        }
        return name to outcome
    }

    private suspend fun fetchQueued(chapter: QueuedChapter, onPage: (pages: Int, of: Int) -> Unit): Outcome = coroutineScope {
        var outcome = Outcome.REMOVED
        val job = launch(start = CoroutineStart.LAZY) { outcome = fetched(chapter, onPage) }
        lock.withLock { if (chapter in queue.value) active = chapter to job else job.cancel() }
        job.start()
        try {
            job.join()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    active = null
                    fetching.value = null
                    if (outcome == Outcome.DOWNLOADED || outcome == Outcome.UNAVAILABLE) save(queue.value - chapter)
                }
            }
        }
        outcome
    }

    private suspend fun fetched(chapter: QueuedChapter, onPage: (pages: Int, of: Int) -> Unit): Outcome = try {
        fetchChapter(chapter, onPage)
        Outcome.DOWNLOADED
    } catch (error: CancellationException) {
        throw error
    } catch (_: ChapterUnavailable) {
        Outcome.UNAVAILABLE
    } catch (_: SourceOff) {
        Outcome.SOURCE_OFF
    } catch (_: Exception) {
        Outcome.LATER
    }

    private suspend fun fetchChapter(chapter: QueuedChapter, onPage: (pages: Int, of: Int) -> Unit) {
        val series = repository.series(chapter.series)?.series ?: throw ChapterUnavailable("The series is not stored")
        val entry = repository.listedEntry(chapter.series, chapter.entry)?.entry
            ?: throw ChapterUnavailable("The source no longer lists this chapter")
        val owner = seriesOfflineOwner(chapter.series)
        if (store.retain(PostId(chapter.series.source, entry.address), owner) != null) return
        val source = this.source(chapter.series.source) ?: throw SourceOff()
        val pages = when (val media = attempts { source.media(entry) }) {
            is EntryMedia.Pages -> media.pages
            is EntryMedia.Streams -> throw ChapterUnavailable("An episode cannot be downloaded")
        }.ifEmpty { throw ChapterUnavailable("The chapter has no pages") }
        if (pages.any { it.url == null }) throw ChapterUnavailable("A page has no address")
        fetching.value = chapter to 0f
        onPage(0, pages.size)
        val done = AtomicInteger()
        val post = chapterPost(series, entry, covers.address(series), pages)
        store.save(post, pages, owner, parallel = PARALLEL_PAGES) { page, file ->
            val type = attempts { fetch(checkNotNull(page.url), file) }
            val count = done.incrementAndGet()
            fetching.value = chapter to count.toFloat() / pages.size
            onPage(count, pages.size)
            type
        }
    }

    private suspend fun <T> attempts(block: suspend () -> T): T {
        repeat(PAGE_ATTEMPTS - 1) { attempt ->
            runCatchingExceptionsPreservingCancellation {
                return block()
            }.onFailure {
                delay(retryDelay * (attempt + 1))
            }
        }
        return block()
    }

    private suspend fun names(chapter: QueuedChapter): ChapterName {
        val series = repository.series(chapter.series)?.series?.title.orEmpty()
        val name = repository.storedEntry(chapter.series, chapter.entry)?.entry?.name.orEmpty()
        return ChapterName(chapter.series, series, name)
    }

    private suspend fun remove(matches: (QueuedChapter) -> Boolean) {
        lock.withLock {
            load()
            if (queue.value.any(matches)) save(queue.value.filterNot(matches))
            active?.takeIf { matches(it.first) }?.second
        }?.let { job ->
            job.cancel()
            job.join()
        }
    }

    private suspend fun load() {
        if (loaded) return
        queue.value = files.read(queueFile, emptyList(), QUEUE_SERIALIZER).mapNotNull(QueuedChapterRecord::toDomain)
        loaded = true
    }

    private suspend fun save(chapters: List<QueuedChapter>) {
        files.write(queueFile, chapters.map(QueuedChapterRecord::fromDomain), QUEUE_SERIALIZER)
        queue.value = chapters
    }

    private fun OfflineMediaSnapshot.chapters(id: SeriesId): Set<String> =
        postsByOwner[seriesOfflineOwner(id)].orEmpty().mapTo(HashSet()) { it.sourcePostId }

    private fun OfflineMediaSnapshot.leadPages(id: SeriesId): Map<String, List<ImageRef>> =
        postsByOwner[seriesOfflineOwner(id)].orEmpty().mapNotNull { post -> leadPages[post]?.let { post.sourcePostId to it } }.toMap()

    companion object {
        const val PAGE_ATTEMPTS = 3
        const val NEXT_UNREAD_COUNT = 5
        private const val PARALLEL_PAGES = 3
        private val QUEUE_SERIALIZER = ListSerializer(QueuedChapterRecord.serializer())
    }
}

private enum class Outcome { DOWNLOADED, UNAVAILABLE, SOURCE_OFF, LATER, REMOVED }

private class Held(var changes: Int) {
    val sources = HashSet<SourceKey>()
    val off = HashSet<SourceKey>()

    fun releaseOff(changes: Int) {
        this.changes = changes
        sources -= off
        off.clear()
    }
}

private class SourceOff : IOException("The series' source is not on")

fun chaptersToDownload(choice: DownloadChoice, series: StoredSeries, entries: List<StoredSeriesEntry>, present: Set<String>): List<String> {
    val ordered = entries.inReadingOrder()
    val picked = when (choice) {
        DownloadChoice.ALL -> ordered
        DownloadChoice.ALL_UNREAD -> ordered.filter { !it.read }
        DownloadChoice.NEXT_UNREAD -> {
            val from = continueEntry(series, entries)?.let(ordered::indexOf)?.coerceAtLeast(0) ?: 0
            ordered.drop(from).filter { !it.read && it.entry.address !in present }.take(SeriesDownloads.NEXT_UNREAD_COUNT)
        }
    }
    return picked.map { it.entry.address }.filter { it !in present }
}

@Serializable
private data class QueuedChapterRecord(
    @SerialName("source") val source: String,
    @SerialName("series") val series: String,
    @SerialName("entry") val entry: String,
    @SerialName("automatic") val automatic: Boolean = false,
) {
    fun toDomain() = SourceKey.parse(source)?.let { QueuedChapter(SeriesId(it, series), entry, automatic) }

    companion object {
        fun fromDomain(chapter: QueuedChapter) = QueuedChapterRecord(chapter.series.source.id, chapter.series.address, chapter.entry, chapter.automatic)
    }
}
