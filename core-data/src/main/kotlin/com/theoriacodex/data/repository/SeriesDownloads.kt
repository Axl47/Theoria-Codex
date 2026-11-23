package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesSource
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

/**
 * How many chapters a drain finished and how many it gave up on, and whether it stopped at a failure that may pass, such
 * as no connection, a busy or refusing server, or a source that is off, which leaves the chapter queued to try again.
 */
data class DrainResult(val downloaded: Int, val failed: Int, val retry: Boolean = false)

/**
 * A chapter that cannot download however long one waits: its source no longer lists it, answers with no pages or a
 * page without an address, or a page is gone (404 or 410) or is not an image. It leaves the queue; [SeriesDownloads]' fetch throws it for such a page.
 */
class ChapterUnavailable(message: String) : IOException(message)

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
     * Has the drain of one lane run soon: on the networks the download settings allow for chapters the user queued,
     * only on an unmetered one for [automatic] ones. Each lane is its own job, so neither stops or holds up the other's;
     * this replaces a run of the same lane that has not started draining.
     */
    suspend fun schedule(automatic: Boolean)

    /** As [schedule], unless that lane's run is already scheduled or running, which this must not stop. */
    suspend fun ensureScheduled(automatic: Boolean)
}

/**
 * The offline owner under which [id]'s downloaded chapters are kept; [seriesIdOfOfflineOwner] reads it back. A source id
 * never contains a line break.
 */
fun seriesOfflineOwner(id: SeriesId): String = "$SERIES_OWNER_PREFIX${id.source.id}\n${id.address}"

/** The series an offline owner keeps chapters for, or null for other owners. */
fun seriesIdOfOfflineOwner(owner: String): SeriesId? {
    val pieces = owner.takeIf { it.startsWith(SERIES_OWNER_PREFIX) }?.removePrefix(SERIES_OWNER_PREFIX)?.split('\n', limit = 2)
    if (pieces == null || pieces.size != 2 || pieces[1].isEmpty()) return null
    return SourceKey.parse(pieces[0])?.let { SeriesId(it, pieces[1]) }
}

private const val SERIES_OWNER_PREFIX = "series:"

/**
 * One chapter as the Viewer's post: its id is the chapter's address on the series' source, which is also the id its
 * download is kept under offline. Without [pages] the Viewer asks for them when it gets there. The title is what the
 * Viewer's top bar shows.
 */
fun chapterPost(series: Series, entry: SeriesEntry, cover: String?, pages: List<ImageRef> = emptyList()) = Post(
    id = PostId(series.id.source, entry.address),
    preview = ImageRef(url = cover, localPath = null, mime = null),
    full = pages.firstOrNull(),
    pageUrl = null,
    width = null,
    height = null,
    canonicalTags = emptyList(),
    rawTags = emptyList(),
    authorName = null,
    createdAtEpochMs = entry.uploadedAtEpochMs,
    title = "${series.title} · ${entry.name}",
    media = pages,
    mediaCount = pages.size.takeIf { it > 0 },
    galleryLengthUnknown = pages.isEmpty(),
)

/**
 * The chapter download queue and its one worker. A downloaded chapter is the offline copy of its [chapterPost] in
 * [store], kept by [seriesOfflineOwner], and [store] is the truth for what is on the device. The queue is the only
 * other download state, kept in [queueFile] (under `noBackupFilesDir`, and the offline copies are left out of backups
 * too, so a restored device never brings back a queue without its downloads or the other way round).
 *
 * The queue has two lanes: chapters the user queued, and [automatic][QueuedChapter.automatic] ones the check for new
 * chapters queued, each drained by its own job ([drain]). Drains fetch in queue order, one chapter at a time across
 * both lanes, which also keeps each source to one chapter at a time. A chapter's pages are asked for right before it
 * is fetched, since their addresses expire, and fetched a few at a time through [fetch], each tried [PAGE_ATTEMPTS]
 * times. A chapter that is [unavailable][ChapterUnavailable] then leaves the queue, not downloaded; any other failure
 * keeps it queued and stops its lane until the system runs the job again. Removing every offline copy while a chapter
 * is fetched is such a failure: [store] refuses to commit it, so call [cancelAll] first. [scheduler] runs drains:
 * queuing a chapter while its lane has none running schedules one, and [resume] does after the process restarts.
 */
class SeriesDownloads(
    private val store: OfflineMediaStore,
    private val queueFile: File,
    private val repository: SeriesRepository,
    private val sources: suspend () -> List<SeriesSource>,
    /** Writes the image at an address into a file and returns its media type; throws unless a whole image arrived. */
    private val fetch: suspend (url: String, into: File) -> String,
    private val scheduler: DownloadScheduler,
    /** The Delete after reading setting, as it is now. */
    private val deleteAfterReading: suspend () -> Boolean,
    private val files: AtomicJsonFileStore = AtomicJsonFileStore(),
    private val retryDelay: Duration = 2.seconds,
) {
    /** Guards the queue, its file, [draining] and [active]. */
    private val lock = Mutex()

    /** Held while a chapter is fetched, so the lanes take turns chapter by chapter. */
    private val drains = Mutex()
    private val queue = MutableStateFlow<List<QueuedChapter>>(emptyList())
    private val fetching = MutableStateFlow<Pair<QueuedChapter, Float>?>(null)
    private var loaded = false

    /** The lanes with a drain under way, by [QueuedChapter.automatic], each with the token of its newest drain. */
    private val draining = mutableMapOf<Boolean, Any>()
    private var active: Pair<QueuedChapter, Job>? = null

    /** Each chapter of [id] that is queued or on the device, by address. */
    fun states(id: SeriesId): Flow<Map<String, ChapterDownload>> = flow {
        lock.withLock { load() }
        store.load()
        val downloaded = store.snapshot.map { it.chapters(id) }.distinctUntilChanged()
        emitAll(
            combine(downloaded, queue, fetching) { downloaded, queued, current ->
                downloaded.associateWith<String, ChapterDownload> { ChapterDownload.OnDevice } +
                    queued.filter { it.series == id }.associate { chapter ->
                        chapter.entry to ChapterDownload.Queued(current?.takeIf { it.first == chapter }?.second)
                    }
            },
        )
    }

    /** The chapter at [entry] with its pages as files, in reading order; null when it is not on the device. */
    suspend fun chapter(id: SeriesId, entry: String): Post? = store.find(PostId(id.source, entry))

    /** The first page, as a file, of each of [id]'s chapters on the device, by address; read from memory. */
    suspend fun firstPages(id: SeriesId): Map<String, ImageRef> {
        store.load()
        val snapshot = store.snapshot.value
        return snapshot.postsByOwner[seriesOfflineOwner(id)].orEmpty().mapNotNull { post -> snapshot.firstPages[post]?.let { post.sourcePostId to it } }.toMap()
    }

    /** The addresses of [id]'s chapters on the device. */
    suspend fun downloaded(id: SeriesId): Set<String> {
        store.load()
        return store.snapshot.value.chapters(id)
    }

    /**
     * Loads the saved queue, deletes what [deleteRead] would, since the process may have ended with chapters read, and
     * has the queue drained. Call once per process.
     */
    suspend fun resume() {
        val queued = lock.withLock {
            load()
            queue.value
        }
        deleteRead()
        queued.map { it.automatic }.distinct().forEach { scheduler.ensureScheduled(it) }
    }

    /**
     * Schedules each waiting lane again whenever what it may have waited for changes: both lanes when [sources] (the
     * sources that are on) change, and the user's lane when its [network] settings do, since only its job's network
     * follows them. Scheduling again replaces the lane's job with today's requirements and no failure backoff. A sources
     * change leaves a draining lane alone; a network change replaces the user's job even while it drains, so it stops
     * on a network the settings no longer allow, and the chapter it was fetching stays queued. Changes count from each
     * flow's first value. Never returns.
     */
    suspend fun rescheduleOnChanges(sources: Flow<*>, network: Flow<*>): Unit = coroutineScope {
        launch {
            sources.distinctUntilChanged().drop(1).collect {
                reschedule(automatic = false, evenDraining = false)
                reschedule(automatic = true, evenDraining = false)
            }
        }
        launch { network.distinctUntilChanged().drop(1).collect { reschedule(automatic = false, evenDraining = true) } }
    }

    /** Schedules the [automatic] lane if it has chapters queued, and unless [evenDraining], only while it is not draining. */
    private suspend fun reschedule(automatic: Boolean, evenDraining: Boolean) {
        val schedule = lock.withLock {
            load()
            queue.value.any { it.automatic == automatic } && (evenDraining || automatic !in draining)
        }
        if (schedule) scheduler.schedule(automatic)
    }

    /**
     * With Delete after reading on, deletes each downloaded chapter that is read, except the one its series was last
     * opened at. Called once no reader is open, and at start; it stops once [noReaderOpen] turns false, so a chapter a
     * reader opened meanwhile keeps its files.
     */
    suspend fun deleteRead(noReaderOpen: () -> Boolean = { true }) {
        if (!deleteAfterReading()) return
        store.load()
        store.snapshot.value.postsByOwner.forEach { (owner, posts) ->
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

    /**
     * Queues [entries] of [id] in this order, leaving out those already queued or on the device. Like every change
     * below, it finishes once started, even when its caller is cancelled, so the queue and its file stay in step.
     */
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

    /** Queues the chapters of [id] that [choice] picks. */
    suspend fun download(id: SeriesId, choice: DownloadChoice) {
        val series = repository.series(id) ?: return
        val present = downloaded(id) + lock.withLock { load(); queue.value.filter { it.series == id }.map { it.entry } }
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
        store.removeOwner(seriesOfflineOwner(id), PostId(id.source, entry))
    }

    /**
     * Deletes everything downloaded for [id] and empties its part of the queue. Following does not matter, and neither
     * does whether the source still lists a chapter. Moving a series to another source calls this for the old one.
     */
    suspend fun deleteSeries(id: SeriesId) = withContext(NonCancellable) {
        remove { it.series == id }
        store.removeOwner(seriesOfflineOwner(id))
    }

    /** Empties the queue, stopping the chapter being fetched; clearing [store] then deletes every chapter on the device. */
    suspend fun cancelAll() = withContext(NonCancellable) { remove { true } }

    /**
     * Downloads one lane of the queue, chapters the user queued or [automatic] ones, in order until it is empty or a
     * chapter fails in a way that may pass, which stops the drain with [DrainResult.retry]. Cancelling it stops the
     * chapter being fetched, which stays queued for the next drain. A drain that starts while a cancelled one of the same
     * lane is still stopping takes the lane over, so the old one's end does not mark the lane idle.
     */
    suspend fun drain(automatic: Boolean, onProgress: (DrainProgress) -> Unit = {}): DrainResult {
        val token = Any()
        lock.withLock {
            load()
            draining[automatic] = token
        }
        var downloaded = 0
        var failed = 0
        try {
            while (true) {
                when (drains.withLock { fetchNext(automatic, token, downloaded + failed + 1, onProgress) }) {
                    Outcome.DOWNLOADED -> downloaded++
                    Outcome.UNAVAILABLE -> failed++
                    Outcome.LATER -> return DrainResult(downloaded, failed, retry = true)
                    Outcome.REMOVED -> Unit
                    null -> return DrainResult(downloaded, failed)
                }
            }
        } finally {
            withContext(NonCancellable) { lock.withLock { draining.remove(automatic, token) } }
        }
    }

    /**
     * Fetches the first chapter of the [automatic] lane, the [number]th of the drain holding [token]; null when the lane
     * is empty.
     */
    private suspend fun fetchNext(automatic: Boolean, token: Any, number: Int, onProgress: (DrainProgress) -> Unit): Outcome? {
        // The lane stops draining in the same step that finds it empty, so a chapter queued after is scheduled.
        val (next, remaining) = lock.withLock {
            val lane = queue.value.filter { it.automatic == automatic }
            if (lane.isEmpty()) draining.remove(automatic, token)
            lane.firstOrNull() to lane.size
        }
        next ?: return null
        val (series, chapter) = names(next)
        return fetchQueued(next) { pages, of -> onProgress(DrainProgress(series, chapter, next.series, number, number + remaining - 1, pages, of)) }
    }

    /**
     * Fetches [chapter], and takes it out of the queue once it is on the device or [unavailable][ChapterUnavailable].
     * Cancelling the caller leaves it queued.
     */
    private suspend fun fetchQueued(chapter: QueuedChapter, onPage: (pages: Int, of: Int) -> Unit): Outcome = coroutineScope {
        var outcome = Outcome.REMOVED
        // Registered before it can run, so a removal that does not find it as active knows it will never start.
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

    /** How fetching [chapter] ended; a failure is reported, never thrown. */
    private suspend fun fetched(chapter: QueuedChapter, onPage: (pages: Int, of: Int) -> Unit): Outcome = try {
        fetchChapter(chapter, onPage)
        Outcome.DOWNLOADED
    } catch (error: CancellationException) {
        throw error
    } catch (_: ChapterUnavailable) {
        Outcome.UNAVAILABLE
    } catch (_: Exception) {
        Outcome.LATER
    }

    private suspend fun fetchChapter(chapter: QueuedChapter, onPage: (pages: Int, of: Int) -> Unit) {
        val series = repository.series(chapter.series)?.series ?: throw ChapterUnavailable("The series is not stored")
        val entry = repository.entries(chapter.series).firstOrNull { it.entry.address == chapter.entry && it.sourceOrder >= 0 }?.entry
            ?: throw ChapterUnavailable("The source no longer lists this chapter")
        val owner = seriesOfflineOwner(chapter.series)
        // A copy already on the device, such as one saved to a Codex, needs only the series as another owner.
        if (store.retain(PostId(chapter.series.source, entry.address), owner) != null) return
        val source = sources().firstOrNull { it.key == chapter.series.source } ?: throw IOException("The series' source is not on")
        val pages = attempts { source.pages(entry) }.ifEmpty { throw ChapterUnavailable("The chapter has no pages") }
        if (pages.any { it.url == null }) throw ChapterUnavailable("A page has no address")
        fetching.value = chapter to 0f
        onPage(0, pages.size)
        val done = AtomicInteger()
        val post = chapterPost(series, entry, series.coverUrl, pages)
        // Extension pages name no type, and their addresses often no extension, so the type fetched is the page's.
        store.save(post, pages, owner, parallel = PARALLEL_PAGES) { page, file ->
            val type = attempts { fetch(checkNotNull(page.url), file) }
            val count = done.incrementAndGet()
            fetching.value = chapter to count.toFloat() / pages.size
            onPage(count, pages.size)
            type
        }
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

    /** The addresses of [id]'s chapters on the device. */
    private fun OfflineMediaSnapshot.chapters(id: SeriesId): Set<String> =
        postsByOwner[seriesOfflineOwner(id)].orEmpty().mapTo(HashSet()) { it.sourcePostId }

    companion object {
        const val PAGE_ATTEMPTS = 3
        const val NEXT_UNREAD_COUNT = 5
        private const val PARALLEL_PAGES = 3
        private val QUEUE_SERIALIZER = ListSerializer(QueuedChapterRecord.serializer())
    }
}

/** How fetching one queued chapter ended: on the device, unavailable for good, to try again later, or taken out meanwhile. */
private enum class Outcome { DOWNLOADED, UNAVAILABLE, LATER, REMOVED }

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
