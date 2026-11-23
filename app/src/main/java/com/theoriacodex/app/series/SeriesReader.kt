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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads or watches a series in the Viewer. A session's posts are the series' entries in reading order, each arriving
 * with its media when the Viewer reaches it, so the Viewer's own start position, next-post swipe and end card give
 * resume and next entry. A manga series opens as [ViewerSeries.Reading]: chapters are galleries of pages, read in the
 * series' mode. An anime series opens as [ViewerSeries.Watching]: each episode is one video whose streams are fetched
 * each time it opens, and its progress is a playback position. What the Viewer reports back goes to the series, never
 * to History or a Codex. A downloaded chapter's pages are its files, so it reads with no connection and without its
 * extension.
 */
internal class SeriesReader(private val series: SeriesDependencies) {
    /** How many Viewers are reading a series now. */
    private val open = AtomicInteger()
    private val progressWrites = Mutex()

    /**
     * A session over [id]'s stored entries opening at [entryAddress]. A chapter opens on [pageIndex] or, without one,
     * where the user left it; an episode starts where the user left it, so [pageIndex] means nothing for an anime.
     * Null when the series or that entry is not stored.
     */
    suspend fun session(id: SeriesId, entryAddress: String, pageIndex: Int? = null, sessionId: String? = null): ViewerSession? {
        val stored = series.repository.series(id) ?: return null
        val entries = series.repository.entries(id).inReadingOrder()
        val start = entries.indexOfFirst { it.entry.address == entryAddress }.takeIf { it >= 0 } ?: return null
        val context = ViewerLaunchContext(seriesQueryHash(id), start, ViewerStreamSource.SERIES, scrollOffsetHint = 0)
        fun postId(entry: StoredSeriesEntry) = PostId(id.source, entry.entry.address)
        // No extension loads for a session. The cover shows while an entry's media loads; one only an extension's own
        // client can fetch is tagged for it, and that extension loads when the cover is requested.
        val cover = series.covers.address(stored.series)
        val session = when (id.source.seriesKind) {
            SeriesKind.MANGA -> {
                val posts = entries.map { chapterPost(stored.series, it.entry, cover) }
                val resume = entries.associate { postId(it) to it.resumePageIndex() } +
                    listOfNotNull(pageIndex?.let { posts[start].id to it })
                val numbered = entries.mapNotNull { entry ->
                    entry.entry.number?.takeIf { it >= 0f && it.isFinite() }?.let { postId(entry) to seriesChapterMark(stored.series.title, entry.entry.name, it) }
                }.toMap()
                val marks = withFirstPages(numbered, posts, series.downloads.firstPages(id).first())
                ViewerSession(
                    posts, context, initialMediaIndices = resume, series = ViewerSeries.Reading(stored.readingModeOrGuess()),
                    stripMarks = marks,
                )
            }
            // Where an episode starts is a time; its one media item is never paged, and the strip carries no marks.
            SeriesKind.ANIME -> ViewerSession(
                entries.map { episodePost(stored.series, it.entry, cover) }, context,
                series = ViewerSeries.Watching(entries.associate { postId(it) to it.resumePositionMs() }),
            )
        }
        return if (sessionId == null) session else session.copy(sessionId = sessionId)
    }

    /**
     * The entry [postId] of the series [queryHash] names, with its pages, or its streams when it is an episode; null
     * when the source lists no pages for a chapter. Media of the wrong shape for the series' kind, or an episode with
     * no stream that can play, is a failure the Viewer shows. Each call asks the source again: the media's addresses
     * expire, which is also how the Viewer recovers an episode whose stream stopped working.
     */
    suspend fun resolve(queryHash: String?, postId: PostId): Post? {
        val id = checkNotNull(seriesIdOf(queryHash)) { "Not a series session" }
        val stored = checkNotNull(series.repository.series(id)) { "The series is not stored" }
        val entry = checkNotNull(series.repository.entries(id).firstOrNull { it.entry.address == postId.sourcePostId }) {
            "This entry is no longer listed"
        }
        // A downloaded chapter's pages are its files. Its cover is tagged as in a session, so only the Viewer's
        // request for that cover can load the extension; offline, the cover fails and the pages still read.
        val downloaded = series.downloads.chapter(id, entry.entry.address)?.media
        val kind = id.source.seriesKind
        val source = if (downloaded == null) source(id.source) else null
        // Logged, without naming the entry, so a lookup can be timed against playback: the next episode's starts
        // before the current one ends.
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

    /**
     * The user is on [page] of [post], an entry of [session]'s series, whose kind the session says. A chapter whose
     * pages have not arrived shows only its cover, which is not a page read; the Viewer reports the chapter again once
     * they have. An episode's one media item is not a page: it showing makes the episode the series' last opened one
     * (Continue), with no length, so where it was left stays; its progress is [recordPlaybackProgress].
     */
    fun recordPageProgress(session: ViewerSession, post: Post, page: Int) {
        val id = seriesIdOf(session.context.queryHash) ?: return
        when (session.series) {
            is ViewerSeries.Reading -> if (post.media.isNotEmpty()) {
                record { recordProgress(id, post.id.sourcePostId, page.toLong(), post.media.size.toLong()) }
            }
            is ViewerSeries.Watching -> record { recordProgress(id, post.id.sourcePostId, 0, null) }
            null -> Unit
        }
    }

    /** The user is at [progress] in the episode [post]; only a watched session's entries have playback to record. */
    fun recordPlaybackProgress(session: ViewerSession, post: Post, progress: ViewerPlaybackProgress) {
        if (session.series !is ViewerSeries.Watching) return
        val id = seriesIdOf(session.context.queryHash) ?: return
        record { recordProgress(id, post.id.sourcePostId, progress.positionMs, progress.durationMs.takeIf { it > 0 }) }
    }

    /**
     * Writes progress in the app's scope, so a report the closing Viewer sends still lands after its navigation entry
     * is gone, and one at a time, in the order reported: a later position never loses to an earlier one.
     */
    private fun record(write: suspend SeriesRepository.() -> Unit) {
        series.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            progressWrites.withLock {
                runCatchingPreservingCancellation { series.repository.write() }
                    .onFailure { Diagnostics.report("series", "Progress could not be saved", it) }
            }
        }
    }

    /** A Viewer started reading a series; [closed] says when it stops. */
    fun opened() {
        open.incrementAndGet()
    }

    /**
     * A Viewer stopped reading a series. Once none is reading, chapters Delete after reading removes are deleted (see
     * [com.theoriacodex.data.repository.SeriesDownloads.deleteRead]): never while a reader is open. Returns at once.
     */
    fun closed() {
        if (open.decrementAndGet() > 0) return
        series.scope.launch {
            runCatchingPreservingCancellation { series.downloads.deleteRead { open.get() == 0 } }
                .onFailure { Diagnostics.report("series", "Read chapters could not be deleted", it) }
        }
    }

    /** A mode chosen in any chapter is the series' mode. */
    suspend fun setReadingMode(session: ViewerSession, mode: GalleryReadingMode) {
        seriesIdOf(session.context.queryHash)?.let { series.repository.setReadingMode(it, mode) }
    }

    /** [session]'s strip marks from now on, following its chapters as they are downloaded or deleted; none for episodes. */
    fun stripMarks(session: ViewerSession): Flow<Map<PostId, ViewerStripMark>> {
        if (session.series !is ViewerSeries.Reading) return emptyFlow()
        val id = seriesIdOf(session.context.queryHash) ?: return emptyFlow()
        return series.downloads.firstPages(id).map { withFirstPages(session.stripMarks, session.posts, it) }
    }

    /** The mode [session]'s series is read in from now on; the session itself carries the mode it opens in. */
    fun readingMode(session: ViewerSession): Flow<GalleryReadingMode> {
        val id = seriesIdOf(session.context.queryHash) ?: return emptyFlow()
        return series.repository.observeSeries(id).mapNotNull { it?.readingModeOrGuess() }
    }

    /**
     * What [error] and its causes are, by kind only: an extension's own messages can hold a chapter's path or title,
     * which the log must not keep.
     */
    private fun failureKinds(error: Throwable): String = generateSequence(error) { it.cause }.take(MAX_CAUSES).joinToString(" < ") { cause ->
        cause.javaClass.simpleName + ((cause as? SourceAdapterException)?.let { "(${it.reason})" } ?: "")
    }

    private suspend fun source(key: SourceKey) =
        checkNotNull(series.source(key)) { "The extension for this series is not installed" }
}

/** [marks] with each downloaded chapter's first page from [firstPages] (by address), so the strip shows it rather than the shared cover. */
private fun withFirstPages(marks: Map<PostId, ViewerStripMark>, posts: List<Post>, firstPages: Map<String, ImageRef>) =
    posts.associate { it.id to (marks[it.id] ?: ViewerStripMark()).copy(image = firstPages[it.id.sourcePostId]) }.filterValues { it != ViewerStripMark() }

/** The session key of a series' Viewer stream; [seriesIdOf] reads it back. A source id never contains `:`. */
internal fun seriesQueryHash(id: SeriesId): String = "$SERIES_QUERY_PREFIX${id.source.id}:${id.address}"

internal fun seriesIdOf(queryHash: String?): SeriesId? {
    val pieces = queryHash?.takeIf { it.startsWith(SERIES_QUERY_PREFIX) }?.removePrefix(SERIES_QUERY_PREFIX)?.split(':', limit = 2)
    if (pieces == null || pieces.size != 2 || pieces[1].isEmpty()) return null
    return SourceKey.parse(pieces[0])?.let { SeriesId(it, pieces[1]) }
}

/** The user's choice for the series, else a guess from what is stored about it. */
private fun StoredSeries.readingModeOrGuess() = seriesReadingMode(readingMode, series.genres, series.language)

private const val SERIES_QUERY_PREFIX = "series:"
private const val WRONG_MEDIA = "This extension served the wrong kind of media"
private const val NO_STREAM = "No stream found for this episode"
private const val LOOKING_UP_EPISODE = "Looking up an episode"
private const val LOOKING_UP_CHAPTER = "Looking up a chapter"

/** What an entry's media is called in the log, by the series' kind. */
private val SeriesKind.mediaName get() = when (this) {
    SeriesKind.MANGA -> "chapter pages"
    SeriesKind.ANIME -> "episode streams"
}

/**
 * A chapter's strip mark: its [number] as a reader writes it (12, or 10.5 for a part-chapter) over the series cover,
 * and a spoken name that says the number when the chapter's own [name] does not.
 */
internal fun seriesChapterMark(seriesTitle: String, name: String, number: Float): ViewerStripMark {
    val shown = if (number % 1f == 0f) number.toLong().toString() else number.toString()
    val named = Regex("""(?<![\d.])${Regex.escape(shown)}(?!\d|\.\d)""").containsMatchIn(name)
    return ViewerStripMark(shown, "$seriesTitle · ${if (named) name else "Chapter $shown, $name"}")
}

/** Enough of a failure's causes to tell a refusal from a parse error, without a runaway chain. */
private const val MAX_CAUSES = 4
