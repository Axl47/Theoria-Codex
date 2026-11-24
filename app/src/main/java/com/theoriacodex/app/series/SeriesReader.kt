package com.theoriacodex.app.series

import com.theoriacodex.app.di.SeriesDependencies
import com.theoriacodex.app.viewer.ViewerSeries
import com.theoriacodex.app.viewer.ViewerPlaybackProgress
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerStripMark
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.repository.chapterPost
import com.theoriacodex.data.repository.episodePost
import com.theoriacodex.data.repository.inReadingOrder
import com.theoriacodex.data.repository.resumePageIndex
import com.theoriacodex.data.repository.resumePageOffset
import com.theoriacodex.data.repository.resumePositionMs
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.seriesKind
import com.theoriacodex.domain.series.seriesReadingMode
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SeriesReader(private val series: SeriesDependencies) {
    private val open = AtomicInteger()
    private val progressWrites = Mutex()

    suspend fun session(id: SeriesId, entryAddress: String, pageIndex: Int? = null, sessionId: String? = null): ViewerSession? {
        val stored = series.repository.series(id) ?: return null
        val entries = series.repository.entries(id).inReadingOrder()
        val start = entries.indexOfFirst { it.entry.address == entryAddress }.takeIf { it >= 0 } ?: return null
        val context = ViewerLaunchContext(seriesQueryHash(id), start, ViewerStreamSource.SERIES, scrollOffsetHint = 0)
        fun postId(entry: StoredSeriesEntry) = PostId(id.source, entry.entry.address)
        val cover = series.covers.address(stored.series)
        val session = when (id.source.seriesKind) {
            SeriesKind.MANGA -> {
                val posts = entries.map { chapterPost(stored.series, it.entry, cover) }
                val resume = entries.associate { postId(it) to it.resumePageIndex() } +
                    listOfNotNull(pageIndex?.let { posts[start].id to it })
                val numbered = entries.mapNotNull { entry ->
                    entry.entry.number?.takeIf { it >= 0f && it.isFinite() }?.let { postId(entry) to seriesChapterMark(stored.series.title, entry.entry.name, it) }
                }.toMap()
                val marks = withPictures(numbered, posts, series.downloads.leadPages(id).first(), stored.chapterLeadPage())
                val picked = pageIndex?.takeIf { it != entries[start].resumePageIndex() }?.let { posts[start].id }
                val offsets = entries.mapNotNull { entry -> entry.resumePageOffset()?.let { postId(entry) to it } }.toMap() - setOfNotNull(picked)
                ViewerSession(
                    posts, context, initialMediaIndices = resume, series = ViewerSeries.Reading(stored.readingModeOrGuess(), offsets),
                    stripMarks = marks,
                )
            }
            SeriesKind.ANIME -> ViewerSession(
                entries.map { episodePost(stored.series, it.entry, cover) }, context,
                series = ViewerSeries.Watching(
                    startPositionsMs = entries.associate { postId(it) to it.resumePositionMs() },
                    seriesTitle = stored.series.title,
                ),
            )
        }
        return if (sessionId == null) session else session.copy(sessionId = sessionId)
    }

    suspend fun resolve(queryHash: String?, postId: PostId): Post? {
        val id = checkNotNull(seriesIdOf(queryHash)) { "Not a series session" }
        val stored = checkNotNull(series.repository.series(id)) { "The series is not stored" }
        val entry = checkNotNull(series.repository.listedEntry(id, postId.sourcePostId)) {
            "This entry is no longer listed"
        }
        val downloaded = series.downloads.chapter(id, entry.entry.address)?.media
        val kind = id.source.seriesKind
        val source = if (downloaded == null) source(id.source) else null
        if (source != null) series.log(if (kind == SeriesKind.ANIME) LOOKING_UP_EPISODE else LOOKING_UP_CHAPTER)
        val media = downloaded?.let(EntryMedia::Pages) ?: runCatchingPreservingCancellation { checkNotNull(source).media(entry.entry) }
            .onFailure { Diagnostics.report("series", "${id.source.id} ${kind.mediaName} failed to load: ${failureKinds(it)}") }
            .getOrThrow()
        val cover = series.covers.address(stored.series)
        return when (media) {
            is EntryMedia.Pages -> {
                check(kind == SeriesKind.MANGA) { WRONG_MEDIA }
                media.pages.takeIf { it.isNotEmpty() }?.let { chapterPost(stored.series, entry.entry, cover, it) }
            }
            is EntryMedia.Streams -> {
                check(kind == SeriesKind.ANIME) { WRONG_MEDIA }
                episodePost(stored.series, entry.entry, cover, media.streams).also { check(it.media.isNotEmpty()) { NO_STREAM } }
            }
        }
    }

    fun recordPageProgress(session: ViewerSession, post: Post, page: Int, pageOffset: Float? = null) {
        val id = seriesIdOf(session.context.queryHash) ?: return
        when (session.series) {
            is ViewerSeries.Reading -> if (post.media.isNotEmpty()) {
                series.scope.launch { series.pictures.compare(id, mapOf(post.id.sourcePostId to post.media.first())) }
                record { recordProgress(id, post.id.sourcePostId, page.toLong(), post.media.size.toLong(), pageOffset) }
            }
            is ViewerSeries.Watching -> record { recordProgress(id, post.id.sourcePostId, 0, null) }
            null -> Unit
        }
    }

    fun recordPlaybackProgress(session: ViewerSession, post: Post, progress: ViewerPlaybackProgress) {
        if (session.series !is ViewerSeries.Watching) return
        val id = seriesIdOf(session.context.queryHash) ?: return
        record { recordProgress(id, post.id.sourcePostId, progress.positionMs, progress.durationMs.takeIf { it > 0 }) }
    }

    private fun record(write: suspend SeriesRepository.() -> Unit) {
        series.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            progressWrites.withLock {
                runCatchingPreservingCancellation { series.repository.write() }
                    .onFailure { Diagnostics.report("series", "Progress could not be saved", it) }
            }
        }
    }

    fun opened() {
        open.incrementAndGet()
    }

    fun closed() {
        if (open.decrementAndGet() > 0) return
        series.scope.launch {
            runCatchingPreservingCancellation { series.downloads.deleteRead { open.get() == 0 } }
                .onFailure { Diagnostics.report("series", "Read chapters could not be deleted", it) }
        }
    }

    suspend fun setReadingMode(session: ViewerSession, mode: GalleryReadingMode) {
        seriesIdOf(session.context.queryHash)?.let { series.repository.setReadingMode(it, mode) }
    }

    fun stripMarks(session: ViewerSession): Flow<Map<PostId, ViewerStripMark>> {
        if (session.series !is ViewerSeries.Reading) return emptyFlow()
        val id = seriesIdOf(session.context.queryHash) ?: return emptyFlow()
        val leads = series.downloads.leadPages(id)
        return channelFlow {
            launch { leads.collectLatest { downloaded -> series.pictures.compare(id, downloaded.mapValues { it.value.first() }) } }
            combine(leads, series.pictures.leadPage(id)) { pages, lead -> withPictures(session.stripMarks, session.posts, pages, lead) }.collect { send(it) }
        }
    }

    fun readingMode(session: ViewerSession): Flow<GalleryReadingMode> {
        val id = seriesIdOf(session.context.queryHash) ?: return emptyFlow()
        return series.repository.observeSeries(id).mapNotNull { it?.readingModeOrGuess() }
    }

    private fun failureKinds(error: Throwable): String = generateSequence(error) { it.cause }.take(MAX_CAUSES).joinToString(" < ") { cause ->
        cause.javaClass.simpleName + ((cause as? SourceAdapterException)?.let { "(${it.reason})" } ?: "")
    }

    private suspend fun source(key: SourceKey) =
        checkNotNull(series.source(key)) { "The extension for this series is not installed" }
}

private fun withPictures(marks: Map<PostId, ViewerStripMark>, posts: List<Post>, downloads: Map<String, List<ImageRef>>, lead: Int) =
    posts.associate { post ->
        val pages = downloads[post.id.sourcePostId].orEmpty()
        post.id to (marks[post.id] ?: ViewerStripMark()).copy(image = pages.getOrNull(lead) ?: pages.firstOrNull(), page = lead)
    }.filterValues { it != ViewerStripMark() }

internal fun seriesQueryHash(id: SeriesId): String = "$SERIES_QUERY_PREFIX${id.source.id}:${id.address}"

internal fun seriesIdOf(queryHash: String?): SeriesId? {
    val pieces = queryHash?.takeIf { it.startsWith(SERIES_QUERY_PREFIX) }?.removePrefix(SERIES_QUERY_PREFIX)?.split(':', limit = 2)
    if (pieces == null || pieces.size != 2 || pieces[1].isEmpty()) return null
    return SourceKey.parse(pieces[0])?.let { SeriesId(it, pieces[1]) }
}

private fun StoredSeries.readingModeOrGuess() = seriesReadingMode(readingMode, series.genres, series.language)

private const val SERIES_QUERY_PREFIX = "series:"
private const val WRONG_MEDIA = "This extension served the wrong kind of media"
private const val NO_STREAM = "No stream found for this episode"
private const val LOOKING_UP_EPISODE = "Looking up an episode"
private const val LOOKING_UP_CHAPTER = "Looking up a chapter"

private val SeriesKind.mediaName get() = when (this) {
    SeriesKind.MANGA -> "chapter pages"
    SeriesKind.ANIME -> "episode streams"
}

internal fun seriesChapterMark(seriesTitle: String, name: String, number: Float): ViewerStripMark {
    val shown = if (number % 1f == 0f) number.toLong().toString() else number.toString()
    val named = Regex("""(?<![\d.])${Regex.escape(shown)}(?!\d|\.\d)""").containsMatchIn(name)
    return ViewerStripMark(shown, "$seriesTitle · ${if (named) name else "Chapter $shown, $name"}")
}

private const val MAX_CAUSES = 4
