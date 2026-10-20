package com.theoriacodex.app.codex

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.FileBackedCacheRepository
import com.theoriacodex.data.repository.InMemoryCacheRepository
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodexCollectionSourceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `save relevance reads all collection members rather than bounded covers`() = runTest {
        val repository = InMemoryCodexRepository()
        repository.ensureCodex("target", "Target")
        val posts = (1..12).map { testPost(sourcePostId = "$it").copy(canonicalTags = listOf("cat")) }
        repository.addItems("target", posts)
        val source = CodexCollectionSource(repository, folder.root, InMemoryCacheRepository())
        assertEquals(mapOf("target" to 1.0), source.observeSaveRelevance(setOf("target"), listOf(posts.first())).first())
        repository.addItems("target", listOf(testPost(sourcePostId = "unmatched").copy(canonicalTags = listOf("dog"))))
        val query = emptySearchQuery().withIncludeTermsAsRequired(listOf(com.theoriacodex.domain.model.SearchTerm("cat")))
        val score = source.observeSaveRelevance(setOf("target"), listOf(posts.first()), query).first().getValue("target")
        assertEquals(0.95 * 12 / 13, score, 0.000001)
        assertEquals(emptyMap<String, Double>(), source.observeSaveRelevance(emptySet(), posts).first())
        assertEquals(emptyMap<String, Double>(), source.observeSaveRelevance(setOf("target"), emptyList()).first())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Library observes named cover refreshes without a collection or cache-size change`() = runTest {
        val cache = FileBackedCacheRepository(folder.root)
        val repository = InMemoryCodexRepository()
        val source = CodexCollectionSource(repository, folder.root, cache, StandardTestDispatcher(testScheduler))
        val snapshots = Channel<CodexCollectionPresentation>(Channel.UNLIMITED)
        backgroundScope.launch { source.observe(emptySet()).collect { snapshots.send(it) } }
        assertEquals(emptyList<CodexCoverCandidate>(), snapshots.receive().coverCandidates[FOLLOWED_CODEX_ID])
        val first = testPost(sourcePostId = "first").let { it.copy(preview = it.preview.copy(localPath = null)) }
        cache.cacheNamedThumbnail(FOLLOWED_CODEX_ID, first)
        val firstCover = snapshots.receive().coverCandidates.getValue(FOLLOWED_CODEX_ID).single() as CodexCoverCandidate.RemoteImage
        assertEquals(first.preview.url, firstCover.url)
        val second = first.copy(preview = first.preview.copy(url = "https://example.com/second.jpg"))
        cache.cacheNamedThumbnail(FOLLOWED_CODEX_ID, second)
        val secondCover = snapshots.receive().coverCandidates.getValue(FOLLOWED_CODEX_ID).single() as CodexCoverCandidate.RemoteImage
        assertEquals(second.preview.url, secondCover.url)
        assertEquals(firstCover.cacheRevision + 1, secondCover.cacheRevision)
        assertEquals(0, cache.observeSnapshot().first().thumbnailCount)
    }

    @Test
    fun `overview never subscribes to full posts and only selected action sheet hydrates tags`() = runTest {
        val stored = InMemoryCodexRepository()
        stored.ensureCodex("active", "Active")
        stored.ensureCodex("other", "Other")
        stored.addItems("active", (1..20).map { testPost(sourcePostId = "$it") })
        val subscribed = mutableListOf<String>()
        val repository = object : CodexRepository by stored {
            override fun observeCodexPosts(codexId: String, sort: CodexSortMode): Flow<List<Post>> {
                subscribed += codexId
                return stored.observeCodexPosts(codexId, sort)
            }
        }
        val source = CodexCollectionSource(repository, folder.root, InMemoryCacheRepository())

        val overview = source.observe(setOf("active")).first()
        assertEquals(mapOf("active" to 20), overview.itemCounts)
        assertEquals(emptyList<String>(), subscribed)
        source.observeActionOptions("active", setOf(SourceKey.PIXIV)).first()
        assertEquals(listOf("active"), subscribed)
    }
}
