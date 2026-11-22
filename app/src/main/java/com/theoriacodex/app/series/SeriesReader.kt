package com.theoriacodex.app.series

import com.theoriacodex.app.di.SeriesDependencies
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.repository.inReadingOrder
import com.theoriacodex.data.repository.resumePageIndex
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.seriesReadingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Reads a series in the Viewer. A session's posts are the series' entries in reading order, each a gallery whose pages
 * arrive when the Viewer reaches it, so the Viewer's own start page, next-post swipe, end card and reading-mode switch
 * give resume, next chapter and modes. What the Viewer reports back goes to the series, never to History or a Codex.
 */
internal class SeriesReader(private val series: SeriesDependencies) {
    /** The entry on screen whose page count is not known yet, and the page it is on; recorded once its pages arrive. */
    @Volatile
    private var awaitingPages: Pair<PostId, Int>? = null

    /**
     * A session over [id]'s stored entries opening at [entryAddress], on [pageIndex] or, without one, where the user
     * left it. Null when the series or that entry is not stored.
     */
    suspend fun session(id: SeriesId, entryAddress: String, pageIndex: Int? = null, sessionId: String? = null): ViewerSession? {
        val stored = series.repository.series(id) ?: return null
        val entries = series.repository.entries(id).inReadingOrder()
        val start = entries.indexOfFirst { it.entry.address == entryAddress }.takeIf { it >= 0 } ?: return null
        val posts = entries.map { chapterPost(stored.series, it.entry) }
        val context = ViewerLaunchContext(seriesQueryHash(id), start, ViewerStreamSource.SERIES, scrollOffsetHint = 0)
        val resume = entries.associate { PostId(id.source, it.entry.address) to it.resumePageIndex() } +
            listOfNotNull(pageIndex?.let { posts[start].id to it })
        return if (sessionId == null) ViewerSession(posts, context, initialMediaIndices = resume)
        else ViewerSession(posts, context, sessionId = sessionId, initialMediaIndices = resume)
    }

    /** The chapter [postId] of the series [queryHash] names, with its pages; null when the source lists none for it. */
    suspend fun resolve(queryHash: String?, postId: PostId): Post? {
        val id = checkNotNull(seriesIdOf(queryHash)) { "Not a series session" }
        val stored = checkNotNull(series.repository.series(id)) { "The series is not stored" }
        val entry = checkNotNull(series.repository.entries(id).firstOrNull { it.entry.address == postId.sourcePostId }) {
            "The chapter is not stored"
        }
        val pages = source(id.source).pages(entry.entry)
        if (pages.isEmpty()) return null
        awaitingPages?.takeIf { it.first == postId }?.let { (_, page) ->
            awaitingPages = null
            series.repository.recordProgress(id, entry.entry.address, page, pages.size)
        }
        return chapterPost(stored.series, entry.entry).copy(
            full = pages.first(),
            media = pages,
            mediaCount = pages.size,
            galleryLengthUnknown = false,
        )
    }

    /** The user is on page [mediaNumber] (from 1) of the chapter [post]. */
    suspend fun recordProgress(session: ViewerSession, post: Post, mediaNumber: Int) {
        val id = seriesIdOf(session.context.queryHash) ?: return
        val pageCount = post.media.size.takeIf { it > 0 }
        awaitingPages = if (pageCount == null) post.id to mediaNumber else null
        series.repository.recordProgress(id, post.id.sourcePostId, mediaNumber, pageCount)
    }

    /** A mode chosen in any chapter is the series' mode. */
    suspend fun setReadingMode(session: ViewerSession, mode: GalleryReadingMode) {
        seriesIdOf(session.context.queryHash)?.let { series.repository.setReadingMode(it, mode) }
    }

    /** The mode [session]'s series is read in: the user's choice, else a guess from the series and its source. */
    fun readingMode(session: ViewerSession): Flow<GalleryReadingMode> {
        val id = seriesIdOf(session.context.queryHash) ?: return emptyFlow()
        return flow {
            val sourceName = series.sources().firstOrNull { it.key == id.source }?.name.orEmpty()
            emitAll(
                series.repository.observeSeries(id).map { stored ->
                    seriesReadingMode(stored?.readingMode, stored?.series?.genres.orEmpty(), sourceName, stored?.series?.language.orEmpty())
                },
            )
        }
    }

    private suspend fun source(key: SourceKey) =
        checkNotNull(series.sources().firstOrNull { it.key == key }) { "The extension for this series is not installed" }
}

/** The session key of a series' Viewer stream; [seriesIdOf] reads it back. A source id never contains `:`. */
internal fun seriesQueryHash(id: SeriesId): String = "$SERIES_QUERY_PREFIX${id.source.id}:${id.address}"

internal fun seriesIdOf(queryHash: String?): SeriesId? {
    val pieces = queryHash?.takeIf { it.startsWith(SERIES_QUERY_PREFIX) }?.removePrefix(SERIES_QUERY_PREFIX)?.split(':', limit = 2)
    if (pieces == null || pieces.size != 2 || pieces[1].isEmpty()) return null
    return SourceKey.parse(pieces[0])?.let { SeriesId(it, pieces[1]) }
}

/**
 * One chapter as the Viewer's post: its id is the chapter's address on the series' source, and it names no pages, so
 * the Viewer asks for them when it gets there. The title is what the Viewer's top bar shows.
 */
internal fun chapterPost(series: Series, entry: SeriesEntry) = Post(
    id = PostId(series.id.source, entry.address),
    preview = ImageRef(url = series.coverUrl, localPath = null, mime = null),
    full = null,
    pageUrl = null,
    width = null,
    height = null,
    canonicalTags = emptyList(),
    rawTags = emptyList(),
    authorName = null,
    createdAtEpochMs = entry.uploadedAtEpochMs,
    title = "${series.title} · ${entry.name}",
    galleryLengthUnknown = true,
)

private const val SERIES_QUERY_PREFIX = "series:"
