package com.theoriacodex.app.comments

import com.theoriacodex.app.source.SharedFlights
import com.theoriacodex.domain.adapter.CommentsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asks a source's [CommentsSourceAdapter] for comment pages and remembers the newest [CACHE_SIZE] pages for the
 * session, so swiping back to a post or reopening its replies sends no request. Requests for one page that
 * overlap share a single provider call; a failure is passed on and never remembered.
 */
class CommentsLoader(
    private val registry: SourceAdapterRegistry,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CommentsLoading {
    /** Identifies one page: a post's comments or one comment's replies, at one page token. */
    private data class PageKey(val post: PostId, val parentId: String?, val pageToken: String?)

    private val flights = SharedFlights<PageKey, CommentPage>()
    private val cache = object : LinkedHashMap<PageKey, CommentPage>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PageKey, CommentPage>) = size > CACHE_SIZE
    }

    override fun supports(source: SourceKey): Boolean = registry.adapterFor(source) is CommentsSourceAdapter

    override suspend fun load(post: Post, pageToken: String?, parent: PostComment?): CommentPage {
        val key = PageKey(post.id, parent?.id, pageToken)
        cached(key)?.let { return it }
        val adapter = registry.adapterFor(post.id.source) as? CommentsSourceAdapter
            ?: return CommentPage(emptyList(), total = 0, nextPageToken = null)
        return withContext(dispatcher) {
            flights.share(key) {
                // A request that finished while this one was starting has already filled the cache.
                cached(key) ?: adapter.comments(post, pageToken, parent).also { page -> synchronized(cache) { cache[key] = page } }
            }
        }
    }

    private fun cached(key: PageKey): CommentPage? = synchronized(cache) { cache[key] }

    private companion object {
        const val CACHE_SIZE = 64
    }
}

/** For containers and tests with no sources behind them: nothing supports comments. */
internal object UnsupportedCommentsLoader : CommentsLoading {
    override fun supports(source: SourceKey): Boolean = false
    override suspend fun load(post: Post, pageToken: String?, parent: PostComment?) =
        CommentPage(emptyList(), total = 0, nextPageToken = null)
}
