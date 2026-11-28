package com.theoriacodex.app.codex

import kotlin.coroutines.EmptyCoroutineContext
import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.related.LikeToggleOutcome
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.UnsupportedRelatedPostsLoader
import com.theoriacodex.app.related.loadedPosts
import com.theoriacodex.app.search.TestAdapter
import com.theoriacodex.app.search.TestRegistry
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.CacheRepository
import com.theoriacodex.data.repository.CacheSnapshot
import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FollowedCodexViewModelTest {
    @Test fun `cover follows the first sorted feed post without refetching on a sort change`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var calls = 0
            val cache = TrackingCacheRepository()
            val owner = owner(cache) { _, _ ->
                calls++
                Page(listOf(testPost(SourceKey.PIXIV, "old").copy(createdAtEpochMs = 10),
                    testPost(SourceKey.PIXIV, "new").copy(createdAtEpochMs = 20)), null)
            }
            store.put("owner", owner)
            val selected = listOf(follow("1"))
            owner.synchronize(selected, setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            assertEquals(listOf(FOLLOWED_CODEX_ID to "new"), cache.namedCalls)
            owner.synchronize(selected, setOf(SourceKey.PIXIV), CodexSortMode.OLDEST_SAVED)
            advanceUntilIdle()
            assertEquals(listOf(FOLLOWED_CODEX_ID to "new", FOLLOWED_CODEX_ID to "old"), cache.namedCalls)
            assertEquals(1, calls)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `first admitted post updates the cover while another author is still loading`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val cache = TrackingCacheRepository()
            val owner = owner(cache) { creator, _ ->
                if (creator.profileId == "2") delay(10_000)
                Page(listOf(testPost(SourceKey.PIXIV, creator.profileId!!)), null)
            }
            store.put("owner", owner)
            owner.synchronize(listOf(follow("1"), follow("2")), setOf(SourceKey.PIXIV))
            runCurrent()
            assertTrue(owner.state.value.loading)
            assertEquals(listOf(FOLLOWED_CODEX_ID to "1"), cache.namedCalls)
            advanceUntilIdle()
            assertEquals(listOf(FOLLOWED_CODEX_ID to "1"), cache.namedCalls)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `initial posts cache the first followed cover without pagination replacing it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val cache = TrackingCacheRepository()
            val owner = owner(cache) { _, token ->
                if (token == null) Page(listOf(testPost(SourceKey.PIXIV, "first")), "next")
                else Page(listOf(testPost(SourceKey.PIXIV, "second")), null)
            }
            store.put("owner", owner)
            owner.synchronize(listOf(follow("1")), setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            owner.loadMore()
            advanceUntilIdle()

            assertEquals(listOf(FOLLOWED_CODEX_ID to "first"), cache.namedCalls)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `empty duplicate pages retain independent continuation and failed creators retry alone`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var failSecond = true
            val calls = mutableListOf<Pair<String?, String?>>()
            val owner = owner { creator, token ->
                calls += creator.profileId to token
                if (creator.profileId == "2" && failSecond) error("unavailable")
                when (token) {
                    null -> Page(listOf(testPost(SourceKey.PIXIV, "shared")), "next")
                    "next" -> Page(emptyList(), "last")
                    else -> Page(listOf(testPost(SourceKey.PIXIV, "last")), null)
                }
            }
            store.put("owner", owner)
            owner.synchronize(listOf(follow("1"), follow("2")), setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            assertEquals(1, owner.state.value.posts.size)
            assertEquals(1, owner.state.value.errors.size)
            assertFalse(owner.state.value.refreshFailedOnly)
            failSecond = false
            owner.retry()
            advanceUntilIdle()
            assertEquals(1, calls.count { it.first == "1" })
            assertEquals(1, owner.state.value.posts.size)
            assertTrue(owner.state.value.errors.isEmpty())
            owner.loadMore()
            advanceUntilIdle()
            assertTrue(owner.state.value.canLoadMore)
            assertEquals(1, owner.state.value.posts.size)
            owner.loadMore()
            advanceUntilIdle()
            assertFalse(owner.state.value.canLoadMore)
            assertEquals(listOf("shared", "last"), owner.state.value.posts.map { it.id.sourcePostId })
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `filter replacement rejects late results and metadata updates do not reload`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var calls = 0
            val owner = owner { creator, _ ->
                calls++
                withContext(NonCancellable) { delay(if (creator.profileId == "1") 1000 else 1) }
                Page(listOf(testPost(SourceKey.PIXIV, creator.profileId!!)), null)
            }
            store.put("owner", owner)
            owner.synchronize(listOf(follow("1")), setOf(SourceKey.PIXIV))
            runCurrent()
            owner.synchronize(listOf(follow("2")), setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            assertEquals(listOf("2"), owner.state.value.posts.map { it.id.sourcePostId })
            owner.synchronize(listOf(follow("2").copy(checkedAtEpochMs = 123)), setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            assertEquals(2, calls)
            owner.synchronize(emptyList(), setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            assertTrue(owner.state.value.posts.isEmpty())
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `creator deadlines isolate failures and no more than two requests overlap`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var active = 0
            var peak = 0
            val owner = owner { creator, _ ->
                active++
                peak = maxOf(peak, active)
                try {
                    delay(if (creator.profileId == "1") 30_000 else 100)
                    Page(listOf(testPost(SourceKey.PIXIV, creator.profileId!!)), null)
                } finally { active-- }
            }
            store.put("owner", owner)
            owner.synchronize((1..4).map { follow(it.toString()) }, setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            assertEquals(2, peak)
            assertEquals(3, owner.state.value.posts.size)
            assertEquals(1, owner.state.value.errors.size)
            assertFalse(owner.state.value.loading)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `related shelf anchors in the shown order and closes when the feed or its order changes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val old = testPost(SourceKey.PIXIV, "old").copy(createdAtEpochMs = 10)
            val new = testPost(SourceKey.PIXIV, "new").copy(createdAtEpochMs = 20)
            val related = listOf(testPost(SourceKey.PIXIV, "related"))
            val owner = owner(relatedLoader = FixedRelatedLoader(related)) { _, _ -> Page(listOf(old, new), null) }
            store.put("owner", owner)
            val selected = listOf(follow("1"))
            owner.synchronize(selected, setOf(SourceKey.PIXIV))
            advanceUntilIdle()

            owner.related.onLikeCommitted(old, LikeToggleOutcome.LIKED)
            advanceUntilIdle()
            val loaded = owner.relatedState.value as RelatedPostsUiState.Loaded
            assertEquals(1, loaded.request.anchorCanonicalIndex)
            assertEquals(related, loaded.loadedPosts)

            owner.synchronize(selected, setOf(SourceKey.PIXIV), CodexSortMode.OLDEST_SAVED)
            assertEquals(RelatedPostsUiState.Idle, owner.relatedState.value)

            owner.related.show(old)
            advanceUntilIdle()
            owner.synchronize(selected + follow("2"), setOf(SourceKey.PIXIV), CodexSortMode.OLDEST_SAVED)
            assertEquals(RelatedPostsUiState.Idle, owner.relatedState.value)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `refresh keeps the loaded posts while offline, reports only a failed refresh, and Retry replaces them with the newest page (ADR 0152)`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var uploads = listOf("old")
            var offline = false
            val owner = owner { creator, _ ->
                check(!offline) { "offline" }
                Page(uploads.map { testPost(SourceKey.PIXIV, "${creator.profileId}-$it") }, null)
            }
            store.put("owner", owner)
            owner.synchronize(listOf(follow("1"), follow("2")), setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            val loadedAt = owner.state.value.loadedAtEpochMs
            uploads = listOf("new")

            offline = true
            owner.refresh()
            owner.refresh()
            advanceUntilIdle()
            assertEquals(listOf("1-old", "2-old"), owner.state.value.posts.map { it.id.sourcePostId })
            assertEquals(2, owner.state.value.errors.size)
            assertTrue(owner.state.value.refreshFailedOnly)
            assertEquals(loadedAt, owner.state.value.loadedAtEpochMs)
            assertFalse(owner.state.value.loading)

            offline = false
            owner.retry()
            advanceUntilIdle()
            assertEquals(listOf("1-new", "2-new"), owner.state.value.posts.map { it.id.sourcePostId })
            assertTrue(owner.state.value.errors.isEmpty())
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `a refresh that cuts off a creator's first load and fails reports a load failure, not a failed refresh (ADR 0152)`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var secondCalls = 0
            val owner = owner { creator, _ ->
                if (creator.profileId == "2" && secondCalls++ == 0) delay(10_000)
                check(creator.profileId != "2") { "offline" }
                Page(listOf(testPost(SourceKey.PIXIV, "${creator.profileId}-old")), null)
            }
            store.put("owner", owner)
            owner.synchronize(listOf(follow("1"), follow("2")), setOf(SourceKey.PIXIV))
            runCurrent()

            owner.refresh()
            advanceUntilIdle()

            assertEquals(listOf("1-old"), owner.state.value.posts.map { it.id.sourcePostId })
            assertEquals(setOf("membership:2"), owner.state.value.errors.keys)
            assertFalse(owner.state.value.refreshFailedOnly)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `a new follow set arriving mid-refresh wins and leaves refresh working`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var slow = false
            var calls = 0
            val owner = owner { creator, _ ->
                calls++
                if (slow) withContext(NonCancellable) { delay(1_000) }
                Page(listOf(testPost(SourceKey.PIXIV, "${creator.profileId}-$calls")), null)
            }
            store.put("owner", owner)
            owner.synchronize(listOf(follow("1")), setOf(SourceKey.PIXIV))
            advanceUntilIdle()

            slow = true
            owner.refresh()
            runCurrent()
            slow = false
            owner.synchronize(listOf(follow("2")), setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            val replaced = owner.state.value.posts.map { it.id.sourcePostId }
            assertEquals(listOf("2"), replaced.map { it.substringBefore('-') })
            assertFalse(owner.state.value.loading)

            owner.refresh()
            advanceUntilIdle()
            val refreshed = owner.state.value.posts.map { it.id.sourcePostId }
            assertEquals(listOf("2"), refreshed.map { it.substringBefore('-') })
            assertNotEquals(replaced, refreshed)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `only a refresh reports refreshing, never the first load or a load more (ADR 0225)`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val owner = owner { creator, token -> Page(listOf(testPost(SourceKey.PIXIV, "${creator.profileId}-${token ?: 0}")), "1".takeIf { token == null }) }
            store.put("owner", owner)
            owner.synchronize(listOf(follow("1")), setOf(SourceKey.PIXIV))
            assertTrue(owner.state.value.loading)
            assertFalse(owner.state.value.refreshing)
            advanceUntilIdle()

            owner.loadMore()
            assertTrue(owner.state.value.loading)
            assertFalse(owner.state.value.refreshing)
            advanceUntilIdle()

            owner.refresh()
            assertTrue(owner.state.value.refreshing)
            advanceUntilIdle()
            assertFalse(owner.state.value.refreshing)
            assertFalse(owner.state.value.loading)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `refresh with no follows fetches nothing and leaves the feed idle`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var calls = 0
            val owner = owner { _, _ -> calls++; Page(emptyList(), null) }
            store.put("owner", owner)
            owner.synchronize(emptyList(), setOf(SourceKey.PIXIV))
            advanceUntilIdle()
            val before = owner.state.value

            owner.refresh()
            advanceUntilIdle()
            assertEquals(0, calls)
            assertEquals(before, owner.state.value)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    private class FixedRelatedLoader(private val posts: List<Post>) : RelatedPostsLoading {
        override fun supports(source: SourceKey) = true
        override suspend fun load(seed: PostId) = posts
    }

    private fun follow(id: String) = FollowedCreator(
        CreatorProfile(SourceKey.PIXIV, "Artist $id", id, uploadsQuery = "user:$id"), "membership:$id",
    )

    private fun owner(
        cache: CacheRepository = TrackingCacheRepository(),
        relatedLoader: RelatedPostsLoading = UnsupportedRelatedPostsLoader,
        fetch: suspend (CreatorProfile, String?) -> Page<Post>,
    ): FollowedCodexViewModel {
        val adapter = object : SourceAdapter by TestAdapter(SourceKey.PIXIV), CreatorPostsSourceAdapter {
            override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?) = fetch(creator, pageToken)
        }
        return FollowedCodexViewModel(
            TestRegistry(listOf(adapter)), cache, relatedLoader, providerContext = EmptyCoroutineContext,
        )
    }

    private class TrackingCacheRepository : CacheRepository {
        val namedCalls = mutableListOf<Pair<String, String>>()
        override fun observeSnapshot(): Flow<CacheSnapshot> = MutableStateFlow(CacheSnapshot(0, 0))
        override suspend fun cacheThumbnail(post: Post) = Unit
        override suspend fun cacheNamedThumbnail(name: String, post: Post) {
            namedCalls += name to post.id.sourcePostId
        }
        override suspend fun cacheFull(post: Post) = Unit
        override suspend fun clearThumbnailCache() = Unit
        override suspend fun clearFullImageCache() = Unit
    }
}
