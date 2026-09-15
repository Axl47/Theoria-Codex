package com.theoriacodex.app.codex

import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.search.TestAdapter
import com.theoriacodex.app.search.TestRegistry
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FollowedCodexViewModelTest {
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

    private fun follow(id: String) = FollowedCreator(
        CreatorProfile(SourceKey.PIXIV, "Artist $id", id, uploadsQuery = "user:$id"), "membership:$id",
    )

    private fun owner(fetch: suspend (CreatorProfile, String?) -> Page<Post>): FollowedCodexViewModel {
        val adapter = object : SourceAdapter by TestAdapter(SourceKey.PIXIV), CreatorPostsSourceAdapter {
            override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?) = fetch(creator, pageToken)
        }
        return FollowedCodexViewModel(TestRegistry(listOf(adapter)))
    }
}
