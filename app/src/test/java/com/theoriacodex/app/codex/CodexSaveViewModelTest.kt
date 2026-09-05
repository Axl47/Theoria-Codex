package com.theoriacodex.app.codex

import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.codex.transfer.CodexTransferService
import com.theoriacodex.app.testing.InMemoryCodexLikesTransactions
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CacheRepository
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.InMemoryCacheRepository
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.stubs.StubAdapterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodexSaveViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val codices = InMemoryCodexRepository()
    private val statistics = InMemoryStatisticsRepository()
    private val posts = listOf(testPost(sourcePostId = "one"), testPost(sourcePostId = "two"))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `For You statistic waits for the complete durable save`() = runTest {
        val commit = CompletableDeferred<Unit>()
        val repository = gatedRepository(commit)
        val owner = owner(repository)

        owner.save("target", posts, cacheFullImage = false, fromForYou = true, newCollectionName = "Saved")
        runCurrent()

        assertTrue(codices.observeCodexItems("target").first().isEmpty())
        assertEquals(0L, statistics.observeStatistics().first().forYouSaveCount)
        commit.complete(Unit)
        advanceUntilIdle()

        assertEquals(posts.map(Post::id).toSet(), savedIds())
        assertEquals(1L, statistics.observeStatistics().first().forYouSaveCount)
        assertEquals("2 posts saved to Codex.", owner.effects.first())
    }

    @Test
    fun `failed durable save reports failure without caching or counting a For You save`() = runTest {
        val repository = object : CodexRepository by codices {
            override suspend fun addItems(codexId: String, posts: List<Post>): Int = error("disk full")
        }
        var cacheCalls = 0
        val cache = object : CacheRepository by InMemoryCacheRepository() {
            override suspend fun cacheThumbnail(post: Post) { cacheCalls++ }
            override suspend fun cacheFull(post: Post) { cacheCalls++ }
        }
        val owner = owner(repository, cache)

        owner.save("target", posts, cacheFullImage = true, fromForYou = true)
        advanceUntilIdle()

        assertTrue(savedIds().isEmpty())
        assertEquals(0, cacheCalls)
        assertEquals(0L, statistics.observeStatistics().first().forYouSaveCount)
        assertEquals("Could not save posts. Please try again.", owner.effects.first())
    }

    @Test
    fun `cache failure preserves all saved posts and reports the offline copy problem`() = runTest {
        val fullCopies = mutableListOf<Post>()
        val cache = object : CacheRepository by InMemoryCacheRepository() {
            override suspend fun cacheThumbnail(post: Post) {
                assertEquals(posts.map(Post::id).toSet(), savedIds())
                if (post == posts.first()) error("thumbnail cache unavailable")
            }

            override suspend fun cacheFull(post: Post) {
                fullCopies += post
            }
        }
        val owner = owner(cache = cache)

        owner.save("target", posts, cacheFullImage = true, fromForYou = true, newCollectionName = "Saved")
        advanceUntilIdle()

        assertEquals(posts.map(Post::id).toSet(), savedIds())
        assertEquals(posts, fullCopies)
        assertEquals(1L, statistics.observeStatistics().first().forYouSaveCount)
        assertEquals("Posts saved. Some offline copies could not be cached.", owner.effects.first())
    }

    @Test
    fun `dismissing the message collector cannot cancel the owner save job`() = runTest {
        val commit = CompletableDeferred<Unit>()
        val owner = owner(gatedRepository(commit))
        val sheetCollector = launch { owner.effects.first() }
        owner.save("target", posts, cacheFullImage = false, fromForYou = true, newCollectionName = "Saved")
        runCurrent()

        sheetCollector.cancel()
        runCurrent()
        commit.complete(Unit)
        advanceUntilIdle()

        assertEquals(posts.map(Post::id).toSet(), savedIds())
        assertEquals(1L, statistics.observeStatistics().first().forYouSaveCount)
        assertEquals("2 posts saved to Codex.", owner.effects.first())
    }

    @Test
    fun `saves from other routes do not increment For You statistics`() = runTest {
        val owner = owner()
        owner.save("target", posts.take(1), cacheFullImage = false, fromForYou = false, newCollectionName = "Saved")
        advanceUntilIdle()

        assertEquals(0L, statistics.observeStatistics().first().forYouSaveCount)
        assertEquals("Post saved to Codex.", owner.effects.first())
    }

    @Test
    fun `statistics failure cannot turn committed posts into a save error`() = runTest {
        val failingStatistics = object : StatisticsRepository by statistics {
            override suspend fun recordForYouSave() = error("statistics unavailable")
        }
        val owner = owner(statisticsRepository = failingStatistics)
        owner.save("target", posts, cacheFullImage = false, fromForYou = true, newCollectionName = "Saved")
        advanceUntilIdle()

        assertEquals(posts.map(Post::id).toSet(), savedIds())
        assertEquals("2 posts saved to Codex.", owner.effects.first())
    }

    private fun gatedRepository(commit: CompletableDeferred<Unit>): CodexRepository =
        object : CodexRepository by codices {
            override suspend fun addItems(codexId: String, posts: List<Post>): Int {
                commit.await()
                return codices.addItems(codexId, posts)
            }
        }

    private suspend fun savedIds() = codices.observeCodexItems("target").first().map { it.postId }.toSet()

    private fun owner(
        repository: CodexRepository = codices,
        cache: CacheRepository = InMemoryCacheRepository(),
        statisticsRepository: StatisticsRepository = statistics,
    ): CodexSaveViewModel {
        val transfer = CodexTransferService(
            repository,
            InMemoryCodexLikesTransactions(codices = repository),
            cache,
            StubAdapterRegistry(),
            workerDispatcher = dispatcher,
        )
        return CodexSaveViewModel(transfer, repository, statisticsRepository).also { owner ->
            store.put("save", owner)
        }
    }
}
