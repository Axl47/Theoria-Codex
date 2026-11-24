package com.theoriacodex.app.comments

import com.theoriacodex.domain.coroutines.SharedFlights
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

class CommentsLoader(
    private val registry: SourceAdapterRegistry,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CommentsLoading {
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
                cached(key) ?: adapter.comments(post, pageToken, parent).also { page -> synchronized(cache) { cache[key] = page } }
            }
        }
    }

    override fun nativeCommentAnchor(source: SourceKey, commentId: String): String? =
        (registry.adapterFor(source) as? CommentsSourceAdapter)?.nativeCommentAnchor(commentId)

    private fun cached(key: PageKey): CommentPage? = synchronized(cache) { cache[key] }

    private companion object {
        const val CACHE_SIZE = 64
    }
}

object UnsupportedCommentsLoader : CommentsLoading {
    override fun supports(source: SourceKey): Boolean = false
    override suspend fun load(post: Post, pageToken: String?, parent: PostComment?) =
        CommentPage(emptyList(), total = 0, nextPageToken = null)
}
