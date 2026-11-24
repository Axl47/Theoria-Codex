package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
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

/** A queued chapter by name, for a notification: its series' id and title, and the chapter's name. */
data class ChapterName(val seriesId: SeriesId, val series: String, val chapter: String)

/**
 * How a drain ended: how many chapters it [downloaded], the ones it gave up on ([failed]), and whether it should run
 * again later ([retry]) because a chapter failed in a way that may pass, such as no connection or a busy or refusing
 * server. Such a chapter stays queued. [waiting] are the chapters it left queued because their source is off, which
 * only the user can change; turning a source on reschedules the lane ([rescheduleOnChanges]).
 */
data class DrainResult(
    val downloaded: Int,
    val failed: List<ChapterName> = emptyList(),
    val retry: Boolean = false,
    val waiting: List<QueuedChapter> = emptyList(),
)

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
 * Viewer's top bar shows. [cover] is what [SeriesCovers.address] gives for [series].
 */
fun chapterPost(series: Series, entry: SeriesEntry, cover: String?, pages: List<ImageRef> = emptyList()) =
    entryPost(series, entry, cover, pages, mediaCount = pages.size.takeIf { it > 0 })

/**
 * One episode as the Viewer's post, identified as [chapterPost] identifies a chapter. With [streams] its one media item
 * is a video carrying the [playable] ones and no address of its own: a stream's address expires and needs that
 * stream's headers, so nothing that reads only [ImageRef.url] (sharing, downloads, duration probes, frame sampling) may
 * act on it, and what plays is chosen from [ImageRef.streams]. Without them it is its cover, which the Viewer resolves
 * as it does a chapter without pages, but counted as exactly one media item, so it is never paged as a gallery. Like a
 * chapter's, the post is never stored: a series session's posts stay out of History and every Codex.
 */
fun episodePost(series: Series, entry: SeriesEntry, cover: String?, streams: List<MediaStream> = emptyList()): Post {
    val playable = streams.playable()
    val video = playable.takeIf { it.isNotEmpty() }?.let { ImageRef(url = null, localPath = null, mime = null, streams = it) }
    return entryPost(series, entry, cover, listOfNotNull(video), mediaCount = 1)
}

/** What a chapter's and an episode's post share; empty [media] arrives with the Viewer's lookup. */
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
 * keeps it queued, and its source sits out the rest of the drain while the others' chapters go on, until the system
 * runs the job again. Removing every offline copy while a chapter
 * is fetched is such a failure: [store] refuses to commit it, so call [cancelAll] first. [scheduler] runs drains:
 * queuing a chapter while its lane has none running schedules one, and [resume] does after the process restarts.
 */
class SeriesDownloads(
    private val store: OfflineMediaStore,
    private val queueFile: File,
    private val repository: SeriesRepository,
    /** The source a key names when it is on, else null. */
    private val source: suspend (SourceKey) -> SeriesSource?,
    private val covers: SeriesCovers,
    /** Writes the image at an address into a file and returns its media type; throws unless a whole image arrived. */
    private val fetch: suspend (url: String, into: File) -> String,
    private val scheduler: DownloadScheduler,
    /** The Delete after reading setting, as it is now. */
    private val deleteAfterReading: suspend () -> Boolean,
    private val files: AtomicJsonFileStore = AtomicJsonFileStore(),
    private val retryDelay: Duration = 2.seconds,
) {
    /** Guards the queue, its file, [draining], [active] and [sourceChanges]. */
    private val lock = Mutex()

    /** Held while a chapter is fetched, so the lanes take turns chapter by chapter. */
    private val drains = Mutex()
    private val queue = MutableStateFlow<List<QueuedChapter>>(emptyList())
    private val fetching = MutableStateFlow<Pair<QueuedChapter, Float>?>(null)
    private var loaded = false

    /** The lanes with a drain under way, by [QueuedChapter.automatic], each with the token of its newest drain. */
    private val draining = mutableMapOf<Boolean, Any>()
    private var active: Pair<QueuedChapter, Job>? = null

    /** Counts changes of the sources that are on, so a drain gives the sources it held back another go. */
    private var sourceChanges = 0

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

    /**
     * The first two pages, as files, of each of [id]'s chapters on the device, by address, read from memory; then again
     * whenever a chapter is downloaded or deleted.
     */
    fun leadPages(id: SeriesId): Flow<Map<String, List<ImageRef>>> = flow {
        store.load()
        emitAll(store.snapshot.map { it.leadPages(id) }.distinctUntilChanged())
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
                lock.withLock { sourceChanges++ }
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

    /**
     * Queues [update]'s new chapters, oldest first, when its series is followed and set to download them. Episodes never
     * download, whatever an anime series' stored setting says. Every place that stores new chapters calls this; a
     * failure, such as a full disk, is reported and never thrown, so storing them still counts as done.
     */
    suspend fun downloadNew(update: SeriesUpdate) {
        if (update.series.id.source.seriesKind != SeriesKind.MANGA) return
        try {
            val series = repository.series(update.series.id) ?: return
            if (!series.followed || !series.downloadNewChapters) return
            download(update.series.id, update.newEntries.asReversed().map { it.address }, automatic = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Diagnostics.report("series", "New chapters could not be queued for download", error)
        }
    }

    /** Which lanes, by [QueuedChapter.automatic], have chapters queued; again whenever that changes. */
    fun queuedLanes(): Flow<Set<Boolean>> = flow {
        lock.withLock { load() }
        emitAll(queue.map { chapters -> chapters.mapTo(HashSet()) { it.automatic } }.distinctUntilChanged())
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
     * Downloads one lane of the queue, chapters the user queued or [automatic] ones, in order until nothing is left it
     * can try. A chapter that fails in a way that may pass stays queued, and the rest of its source's chapters wait with
     * it for the next drain, which [DrainResult.retry] asks for, while other sources' chapters go on; once the sources
     * that are on change, held sources that were off are tried again. Cancelling it stops
     * the chapter being fetched, which stays queued for the next drain. A drain that starts while a cancelled one of the
     * same lane is still stopping takes the lane over, so the old one's end does not mark the lane idle.
     */
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
            // Only the user can turn a source on, which reschedules the lane, so waiting for one asks for no retry.
            return DrainResult(downloaded, failed, retry = (held.sources - held.off).isNotEmpty(), waiting = waiting)
        } finally {
            withContext(NonCancellable) { lock.withLock { draining.remove(automatic, token) } }
        }
    }

    /**
     * Fetches the first chapter of the [automatic] lane whose source is not [held], the [number]th of the drain
     * holding [token], and says how that went; null when the lane has no such chapter.
     */
    private suspend fun fetchNext(
        automatic: Boolean,
        token: Any,
        held: Held,
        number: Int,
        onProgress: (DrainProgress) -> Unit,
    ): Pair<ChapterName, Outcome>? {
        // The lane stops draining in the same step that finds nothing to try, so a chapter queued after is scheduled.
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
        // A copy already on the device, such as one saved to a Codex, needs only the series as another owner.
        if (store.retain(PostId(chapter.series.source, entry.address), owner) != null) return
        val source = this.source(chapter.series.source) ?: throw SourceOff()
        // An episode's streams are played, never downloaded; the series page offers no download for them.
        val pages = when (val media = attempts { source.media(entry) }) {
            is EntryMedia.Pages -> media.pages
            is EntryMedia.Streams -> throw ChapterUnavailable("An episode cannot be downloaded")
        }.ifEmpty { throw ChapterUnavailable("The chapter has no pages") }
        if (pages.any { it.url == null }) throw ChapterUnavailable("A page has no address")
        fetching.value = chapter to 0f
        onPage(0, pages.size)
        val done = AtomicInteger()
        val post = chapterPost(series, entry, covers.address(series), pages)
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

    /** [chapter] by the titles of its series and of the chapter itself, as stored. */
    private suspend fun names(chapter: QueuedChapter): ChapterName {
        val series = repository.series(chapter.series)?.series?.title.orEmpty()
        val name = repository.storedEntry(chapter.series, chapter.entry)?.entry?.name.orEmpty()
        return ChapterName(chapter.series, series, name)
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

    private fun OfflineMediaSnapshot.leadPages(id: SeriesId): Map<String, List<ImageRef>> =
        postsByOwner[seriesOfflineOwner(id)].orEmpty().mapNotNull { post -> leadPages[post]?.let { post.sourcePostId to it } }.toMap()

    companion object {
        const val PAGE_ATTEMPTS = 3
        const val NEXT_UNREAD_COUNT = 5
        private const val PARALLEL_PAGES = 3
        private val QUEUE_SERIALIZER = ListSerializer(QueuedChapterRecord.serializer())
    }
}

/**
 * How fetching one queued chapter ended: on the device, unavailable for good, waiting for its source to be on, to try
 * again later, or taken out meanwhile.
 */
private enum class Outcome { DOWNLOADED, UNAVAILABLE, SOURCE_OFF, LATER, REMOVED }

/**
 * One drain's [sources] sitting out the rest of it after a failure that may pass, and of those, the ones that are
 * [off]. Those that are off get another go once the sources that are on have changed since the count of [changes].
 */
private class Held(var changes: Int) {
    val sources = HashSet<SourceKey>()
    val off = HashSet<SourceKey>()

    fun releaseOff(changes: Int) {
        this.changes = changes
        sources -= off
        off.clear()
    }
}

/** A queued chapter's source is off, removed or failed to load; the chapter waits for it. */
private class SourceOff : IOException("The series' source is not on")

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
