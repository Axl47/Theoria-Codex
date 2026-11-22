package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.mediaFileExtension
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesSource
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Where one chapter stands on this device; a chapter with none is not downloaded. */
sealed interface ChapterDownload {
    /** In the queue: [progress] from 0 to 1 while it is being fetched, null while it waits. */
    data class Queued(val progress: Float?) : ChapterDownload

    data object OnDevice : ChapterDownload
}

/** A chapter waiting to be downloaded. [automatic] ones were queued by the check for new chapters. */
data class QueuedChapter(val series: SeriesId, val entry: String, val automatic: Boolean = false)

/** The chapter a drain is fetching, for its notification: the [number]th of the [of] it has taken on so far. */
data class DrainProgress(val series: String, val chapter: String, val seriesId: SeriesId, val number: Int, val of: Int, val pages: Int, val pageCount: Int)

/** How many chapters a drain finished and how many it gave up on. */
data class DrainResult(val downloaded: Int, val failed: Int)

/** What the series page's Download menu queues. */
enum class DownloadChoice {
    /** The next [NEXT_UNREAD_COUNT] unread chapters from where Continue resumes. */
    NEXT_UNREAD,
    ALL_UNREAD,
    ALL,
}

/** Runs [SeriesDownloads.drain] when the system lets it; the app's job scheduler. */
interface DownloadScheduler {
    /**
     * Has the drain of one lane run soon: on any network for chapters the user queued, only on an unmetered one for
     * [automatic] ones. Each lane is its own job, so neither stops or holds up the other's; this replaces a run of the
     * same lane that has not started draining.
     */
    fun schedule(automatic: Boolean)

    /** As [schedule], unless that lane's run is already scheduled or running, which this must not stop. */
    fun ensureScheduled(automatic: Boolean)
}

/**
 * The chapter download queue and its one worker. The queue is the only download state besides the files, kept in
 * [queueFile] (under `noBackupFilesDir`, like the files, so a restored database never brings back a queue without its
 * downloads or the other way round). [store] is the truth for what is on the device.
 *
 * The queue has two lanes: chapters the user queued, and [automatic][QueuedChapter.automatic] ones the check for new
 * chapters queued, each drained by its own job ([drain]). Drains fetch in queue order, one chapter at a time across
 * both lanes, which also keeps each source to one chapter at a time. A chapter's pages are asked for right before it
 * is fetched, since their addresses expire, and fetched a few at a time through [fetch], each tried [PAGE_ATTEMPTS]
 * times; a chapter that still fails leaves the queue, not downloaded. [scheduler] runs drains: queuing a chapter while
 * its lane has none running schedules one, and [resume] does after the process restarts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesDownloads(
    private val store: SeriesDownloadStore,
    private val queueFile: File,
    private val repository: SeriesRepository,
    private val sources: suspend () -> List<SeriesSource>,
    /** Writes the image at an address into a file and returns its media type; throws unless a whole image arrived. */
    private val fetch: suspend (url: String, into: File) -> String?,
    private val scheduler: DownloadScheduler,
    private val files: AtomicJsonFileStore = AtomicJsonFileStore(),
    private val retryDelay: Duration = 2.seconds,
) {
    /** Guards the queue, its file, [draining] and [active]. */
    private val lock = Mutex()

    /** Held while a chapter is fetched, so the lanes take turns chapter by chapter. */
    private val drains = Mutex()
    private val queue = MutableStateFlow<List<QueuedChapter>>(emptyList())
    private val fetching = MutableStateFlow<Pair<QueuedChapter, Float>?>(null)

    /** Moves whenever chapters arrive on the device or leave it. */
    private val changes = MutableStateFlow(0)
    private var loaded = false

    /** The lanes with a drain under way, by [QueuedChapter.automatic]. */
    private val draining = mutableSetOf<Boolean>()
    private var active: Pair<QueuedChapter, Job>? = null

    /** Each chapter of [id] that is queued or on the device, by address. */
    fun states(id: SeriesId): Flow<Map<String, ChapterDownload>> = flow {
        lock.withLock { load() }
        val onDevice = changes.mapLatest { store.downloaded(id) }
        emitAll(
            combine(onDevice, queue, fetching) { downloaded, queued, current ->
                downloaded.associateWith<String, ChapterDownload> { ChapterDownload.OnDevice } +
                    queued.filter { it.series == id }.associate { chapter ->
                        chapter.entry to ChapterDownload.Queued(current?.takeIf { it.first == chapter }?.second)
                    }
            },
        )
    }

    /** The pages of the chapter at [entry] as files, in reading order; null when it is not on the device. */
    suspend fun pages(id: SeriesId, entry: String) = store.pages(id, entry)

    /** The addresses of [id]'s chapters on the device. */
    suspend fun downloaded(id: SeriesId): Set<String> = store.downloaded(id)

    /** Loads the saved queue, clears what stopped downloads left, and has the queue drained. Call once per process. */
    suspend fun resume() {
        val queued = lock.withLock {
            load()
            if (draining.isEmpty()) store.removeUnfinished()
            queue.value
        }
        queued.map { it.automatic }.distinct().forEach(scheduler::ensureScheduled)
    }

    /**
     * Queues [entries] of [id] in this order, leaving out those already queued or on the device. Like every change
     * below, it finishes once started, even when its caller is cancelled, so the queue and its file stay in step.
     */
    suspend fun download(id: SeriesId, entries: List<String>, automatic: Boolean = false): Unit = withContext(NonCancellable) {
        val onDevice = store.downloaded(id)
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

    /** Queues the chapters of [id] that [choice] picks. */
    suspend fun download(id: SeriesId, choice: DownloadChoice) {
        val series = repository.series(id) ?: return
        val present = store.downloaded(id) + lock.withLock { load(); queue.value.filter { it.series == id }.map { it.entry } }
        download(id, chaptersToDownload(choice, series, repository.entries(id), present))
    }

    /** Queues [update]'s new chapters, oldest first, when its series is followed and set to download them. */
    suspend fun downloadNew(update: SeriesUpdate) {
        val series = repository.series(update.series.id) ?: return
        if (!series.followed || !series.downloadNewChapters) return
        download(update.series.id, update.newEntries.asReversed().map { it.address }, automatic = true)
    }

    /**
     * Deletes the chapter at [entry] from the device, or cancels its download: a chapter being fetched stops, and
     * whatever it got as far as is gone too.
     */
    suspend fun delete(id: SeriesId, entry: String) = withContext(NonCancellable) {
        remove { it.series == id && it.entry == entry }
        store.delete(id, entry)
        changes.update { it + 1 }
    }

    /**
     * Deletes everything downloaded for [id] and empties its part of the queue. Following does not matter, and neither
     * does whether the source still lists a chapter. Moving a series to another source calls this for the old one.
     */
    suspend fun deleteSeries(id: SeriesId) = withContext(NonCancellable) {
        remove { it.series == id }
        store.deleteSeries(id)
        changes.update { it + 1 }
    }

    /** Deletes every download and empties the queue. */
    suspend fun deleteAll() = withContext(NonCancellable) {
        remove { true }
        store.deleteAll()
        changes.update { it + 1 }
    }

    suspend fun sizeBytes(): Long = store.sizeBytes()

    /**
     * Downloads one lane of the queue, chapters the user queued or [automatic] ones, in order until it is empty.
     * Cancelling it stops the chapter being fetched, which stays queued for the next drain.
     */
    suspend fun drain(automatic: Boolean, onProgress: (DrainProgress) -> Unit = {}): DrainResult {
        lock.withLock {
            load()
            draining += automatic
        }
        var downloaded = 0
        var failed = 0
        try {
            while (true) {
                val tookOne = drains.withLock {
                    // The lane stops draining in the same step that finds it empty, so a chapter queued after is scheduled.
                    val (next, remaining) = lock.withLock {
                        val lane = queue.value.filter { it.automatic == automatic }
                        if (lane.isEmpty()) draining -= automatic
                        lane.firstOrNull() to lane.size
                    }
                    if (next == null) return@withLock false
                    val number = downloaded + failed + 1
                    val (series, chapter) = names(next)
                    val report = { pages: Int, of: Int -> onProgress(DrainProgress(series, chapter, next.series, number, number + remaining - 1, pages, of)) }
                    when (fetchQueued(next, report)) {
                        true -> downloaded++
                        false -> failed++
                        null -> Unit
                    }
                    true
                }
                if (!tookOne) break
            }
        } finally {
            withContext(NonCancellable) { lock.withLock { draining -= automatic } }
        }
        return DrainResult(downloaded, failed)
    }

    /**
     * Fetches [chapter] and takes it out of the queue: true once it is on the device, false when it failed, null when it
     * was taken out meanwhile. Cancelling the caller leaves it queued.
     */
    private suspend fun fetchQueued(chapter: QueuedChapter, onPage: (pages: Int, of: Int) -> Unit): Boolean? = coroutineScope {
        var outcome: Boolean? = null
        val job = launch { outcome = fetched(chapter, onPage) }
        lock.withLock { if (chapter in queue.value) active = chapter to job else job.cancel() }
        try {
            job.join()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    active = null
                    fetching.value = null
                    if (outcome != null) save(queue.value - chapter)
                }
                if (outcome == true) changes.update { it + 1 }
            }
        }
        outcome
    }

    /** Whether [chapter] arrived whole; a failure is reported as false, never thrown. */
    private suspend fun fetched(chapter: QueuedChapter, onPage: (pages: Int, of: Int) -> Unit): Boolean = try {
        fetchChapter(chapter, onPage)
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun fetchChapter(chapter: QueuedChapter, onPage: (pages: Int, of: Int) -> Unit) {
        val entry = repository.entries(chapter.series).firstOrNull { it.entry.address == chapter.entry }?.entry
            ?: throw IOException("The source no longer lists this chapter")
        val source = sources().firstOrNull { it.key == chapter.series.source } ?: throw IOException("The series' source is not on")
        val pages = attempts { source.pages(entry) }.ifEmpty { throw IOException("The chapter has no pages") }
        fetching.value = chapter to 0f
        onPage(0, pages.size)
        val done = AtomicInteger()
        val gate = Semaphore(PARALLEL_PAGES)
        store.write(chapter.series, chapter.entry) { staging ->
            coroutineScope {
                pages.mapIndexed { index, page ->
                    async {
                        val file = gate.withPermit {
                            val url = page.url ?: throw IOException("A page has no address")
                            attempts { fetchPage(url, staging, index) }
                        }
                        val count = done.incrementAndGet()
                        fetching.value = chapter to count.toFloat() / pages.size
                        onPage(count, pages.size)
                        file
                    }
                }.awaitAll()
            }
        }
    }

    /** Page [index] at [url], saved in [staging] under its name in the chapter. */
    private suspend fun fetchPage(url: String, staging: File, index: Int): File {
        val partial = File(staging, "$index.part")
        val type = fetch(url, partial)
        val page = File(staging, SeriesDownloadStore.pageFileName(index, mediaFileExtension(type, url)))
        if (!partial.renameTo(page)) throw IOException("Could not save a page")
        return page
    }

    /** [block], tried up to [PAGE_ATTEMPTS] times, waiting longer before each retry. */
    private suspend fun <T> attempts(block: suspend () -> T): T {
        repeat(PAGE_ATTEMPTS - 1) { attempt ->
            try {
                return block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                delay(retryDelay * (attempt + 1))
            }
        }
        return block()
    }

    /** The titles of [chapter]'s series and of the chapter itself, as stored. */
    private suspend fun names(chapter: QueuedChapter): Pair<String, String> {
        val series = repository.series(chapter.series)?.series?.title.orEmpty()
        return series to repository.entries(chapter.series).firstOrNull { it.entry.address == chapter.entry }?.entry?.name.orEmpty()
    }

    /** Takes what [matches] out of the queue; a chapter being fetched stops, and is gone, before this returns. */
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

    /** Must hold [lock]. */
    private suspend fun load() {
        if (loaded) return
        queue.value = files.read(queueFile, emptyList(), QUEUE_SERIALIZER).mapNotNull(QueuedChapterRecord::toDomain)
        loaded = true
    }

    /** Must hold [lock]. */
    private suspend fun save(chapters: List<QueuedChapter>) {
        files.write(queueFile, chapters.map(QueuedChapterRecord::fromDomain), QUEUE_SERIALIZER)
        queue.value = chapters
    }

    companion object {
        const val PAGE_ATTEMPTS = 3
        const val NEXT_UNREAD_COUNT = 5
        private const val PARALLEL_PAGES = 3
        private val QUEUE_SERIALIZER = ListSerializer(QueuedChapterRecord.serializer())
    }
}

/**
 * The chapters [choice] queues, oldest first, leaving out [present]: those already queued or on the device. Next unread
 * counts from the chapter Continue resumes.
 */
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
    /** A source id this build cannot read drops the chapter rather than the queue. */
    fun toDomain() = SourceKey.parse(source)?.let { QueuedChapter(SeriesId(it, series), entry, automatic) }

    companion object {
        fun fromDomain(chapter: QueuedChapter) = QueuedChapterRecord(chapter.series.source.id, chapter.series.address, chapter.entry, chapter.automatic)
    }
}
