package com.theoriacodex.app.series

import com.theoriacodex.app.di.SeriesDependencies
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerStripMark
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.repository.chapterPost
import com.theoriacodex.data.repository.inReadingOrder
import com.theoriacodex.data.repository.resumePageIndex
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.seriesReadingMode
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * Reads a series in the Viewer. A session's posts are the series' entries in reading order, each a gallery whose pages
 * arrive when the Viewer reaches it, so the Viewer's own start page, next-post swipe, end card and reading-mode switch
 * give resume, next chapter and modes. What the Viewer reports back goes to the series, never to History or a Codex.
 * A downloaded chapter's pages are its files, so it reads with no connection and without its extension.
 */
internal class SeriesReader(private val series: SeriesDependencies) {
    /** How many Viewers are reading a series now. */
    private val open = AtomicInteger()

    /**
     * A session over [id]'s stored entries opening at [entryAddress], on [pageIndex] or, without one, where the user
     * left it. Null when the series or that entry is not stored.
     */
    suspend fun session(id: SeriesId, entryAddress: String, pageIndex: Int? = null, sessionId: String? = null): ViewerSession? {
        val stored = series.repository.series(id) ?: return null
        val entries = series.repository.entries(id).inReadingOrder()
        val start = entries.indexOfFirst { it.entry.address == entryAddress }.takeIf { it >= 0 } ?: return null
        // No extension loads for a session. The cover shows while a chapter's pages load; one only an extension's own
        // client can fetch is tagged for it, and that extension loads when the cover is requested.
        val cover = series.covers.address(stored.series)
        val posts = entries.map { chapterPost(stored.series, it.entry, cover) }
        val context = ViewerLaunchContext(seriesQueryHash(id), start, ViewerStreamSource.SERIES, scrollOffsetHint = 0)
        val resume = entries.associate { PostId(id.source, it.entry.address) to it.resumePageIndex() } +
            listOfNotNull(pageIndex?.let { posts[start].id to it })
        val numbered = entries.mapNotNull { entry ->
            entry.entry.number?.takeIf { it >= 0f && it.isFinite() }?.let { PostId(id.source, entry.entry.address) to seriesChapterMark(stored.series.title, entry.entry.name, it) }
        }.toMap()
        val marks = withFirstPages(numbered, posts, series.downloads.firstPages(id).first())
        val session = ViewerSession(
            posts, context, initialMediaIndices = resume, seriesReadingMode = stored.readingModeOrGuess(), stripMarks = marks,
        )
        return if (sessionId == null) session else session.copy(sessionId = sessionId)
    }

    /** The chapter [postId] of the series [queryHash] names, with its pages; null when the source lists none for it. */
    suspend fun resolve(queryHash: String?, postId: PostId): Post? {
        val id = checkNotNull(seriesIdOf(queryHash)) { "Not a series session" }
        val stored = checkNotNull(series.repository.series(id)) { "The series is not stored" }
        val entry = checkNotNull(series.repository.entries(id).firstOrNull { it.entry.address == postId.sourcePostId }) {
            "The chapter is not stored"
        }
        // Nothing is asked of the source for a downloaded chapter.
        val downloaded = series.downloads.chapter(id, entry.entry.address)?.media
        val source = if (downloaded == null) source(id.source) else null
        val pages = downloaded ?: runCatchingPreservingCancellation { checkNotNull(source).pages(entry.entry) }
            .onFailure { Diagnostics.report("series", "${id.source.id} chapter pages failed to load: ${failureKinds(it)}") }
            .getOrThrow()
        if (pages.isEmpty()) return null
        return chapterPost(stored.series, entry.entry, series.covers.address(stored.series), pages)
    }

    /**
     * The user is on page [mediaNumber] (from 1) of the chapter [post]. A chapter whose pages have not arrived shows
     * only its cover, which is not a page read; the Viewer reports the chapter again once they have.
     */
    suspend fun recordProgress(session: ViewerSession, post: Post, mediaNumber: Int) {
        val id = seriesIdOf(session.context.queryHash) ?: return
        if (post.media.isEmpty()) return
        series.repository.recordProgress(id, post.id.sourcePostId, mediaNumber, post.media.size)
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

    /** [session]'s strip marks from now on, following its chapters as they are downloaded or deleted. */
    fun stripMarks(session: ViewerSession): Flow<Map<PostId, ViewerStripMark>> {
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
        checkNotNull(series.sources().firstOrNull { it.key == key }) { "The extension for this series is not installed" }
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
