package com.theoriacodex.app.comments

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.adapter.CommentsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CommentsLoaderTest {
    private val post = testPost(sourcePostId = "1")

    @Test
    fun `supports follows the adapter and a repeated page is served from the session cache`() = runTest {
        val adapter = FakeCommentsAdapter()
        val loader = CommentsLoader(FakeRegistry(adapter))

        assertTrue(loader.supports(SourceKey.PIXIV))
        assertFalse(loader.supports(SourceKey.GELBOORU))
        val first = loader.load(post, pageToken = null)
        assertEquals(first, loader.load(post, pageToken = null))
        assertEquals(1, adapter.requests.size)
    }

    @Test
    fun `pages replies and posts are cached apart`() = runTest {
        val adapter = FakeCommentsAdapter()
        val loader = CommentsLoader(FakeRegistry(adapter))
        val parent = PostComment("c1", "a", null, "b", null, hasReplies = true)

        loader.load(post, null)
        loader.load(post, "p2")
        loader.load(post, null, parent)
        loader.load(testPost(sourcePostId = "2"), null)

        assertEquals(listOf("1//", "1//p2", "1/c1/", "2//"), adapter.requests)
    }

    @Test
    fun `overlapping requests for one page share a single provider call`() = runTest {
        val release = CompletableDeferred<Unit>()
        val adapter = FakeCommentsAdapter(gate = release)
        val loader = CommentsLoader(FakeRegistry(adapter), StandardTestDispatcher(testScheduler))

        val callers = List(3) { async { loader.load(post, null) } }
        release.complete(Unit)
        val pages = callers.awaitAll()

        assertEquals(1, adapter.requests.size)
        assertTrue(pages.all { it == pages.first() })
    }

    @Test
    fun `a failure reaches the caller and is not remembered`() = runTest {
        val adapter = FakeCommentsAdapter().apply { failNext = true }
        val loader = CommentsLoader(FakeRegistry(adapter))

        try {
            loader.load(post, null)
            fail("expected the adapter's failure")
        } catch (error: IllegalStateException) {
            assertEquals("offline", error.message)
        }
        loader.load(post, null)
        assertEquals(2, adapter.requests.size)
    }

    @Test
    fun `the cache keeps only the newest pages`() = runTest {
        val adapter = FakeCommentsAdapter()
        val loader = CommentsLoader(FakeRegistry(adapter))
        val posts = (1..70).map { testPost(sourcePostId = "p$it") }

        posts.forEach { loader.load(it, null) }
        loader.load(posts.last(), null)
        assertEquals(70, adapter.requests.size)
        loader.load(posts.first(), null)
        assertEquals(71, adapter.requests.size)
    }

    @Test
    fun `fetching the first page needs support and a listing that does not say none`() {
        val loader = CommentsLoader(FakeRegistry(FakeCommentsAdapter()))

        assertTrue(loader.shouldFetchFirstPage(post))
        assertTrue(loader.shouldFetchFirstPage(post.copy(commentHint = CommentHint.Some)))
        assertTrue(loader.shouldFetchFirstPage(post.copy(commentHint = CommentHint(4))))
        assertFalse(loader.shouldFetchFirstPage(post.copy(commentHint = CommentHint.None)))
        assertFalse(loader.shouldFetchFirstPage(testPost(source = SourceKey.GELBOORU)))
    }
}

private class FakeCommentsAdapter(private val gate: CompletableDeferred<Unit>? = null) : SourceAdapter, CommentsSourceAdapter {
    override val sourceKey = SourceKey.PIXIV
    override val capabilities = SourceCapabilities(
        supportsSortNewest = true, supportsSortPopular = false, supportsSortTop = false, supportsSortRandom = false,
        supportsExcludeTagsServerSide = false, supportsDateRangeServerSide = false, supportsMinScoreServerSide = false,
        requiresCredentials = false,
    )
    val requests = mutableListOf<String>()
    var failNext = false

    override suspend fun comments(post: Post, pageToken: String?, parent: PostComment?): CommentPage {
        requests += "${post.id.sourcePostId}/${parent?.id.orEmpty()}/${pageToken.orEmpty()}"
        gate?.await()
        if (failNext) {
            failNext = false
            error("offline")
        }
        return CommentPage(listOf(PostComment("c${requests.size}", "a", null, "b", null)), total = null, nextPageToken = null)
    }

    override suspend fun search(query: Query, pageToken: String?): Page<Post> = Page(emptyList(), null)
    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()
    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()
    override suspend fun quickQuery(kind: QuickQueryKind): Query = error("unused")
    override suspend fun resolvePost(id: PostId): Post? = null
}

private class FakeRegistry(private val adapter: SourceAdapter) : SourceAdapterRegistry {
    override fun availableSources(): Set<SourceKey> = setOf(adapter.sourceKey)
    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = adapter.takeIf { it.sourceKey == sourceKey }
    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = UnifiedSearchOrchestrator(mapOf(adapter.sourceKey to adapter))
}
