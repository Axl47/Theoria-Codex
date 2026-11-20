package com.theoriacodex.app.creator

import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.search.TestAdapter
import com.theoriacodex.app.search.TestRegistry
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CreatorFollowsRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreatorFollowsViewModelTest {
    @Test fun `manual refresh isolates unavailable creators and records new latest posts`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val settings = InMemorySettingsRepository()
            val repository = CreatorFollowsRepository(settings)
            val available = CreatorProfile(SourceKey.PIXIV, "One", "1", uploadsQuery = "user:1")
            val unavailable = CreatorProfile(SourceKey.IWARA, "Two", "2", uploadsQuery = "user:2")
            repository.follow(available, listOf(testPost(SourceKey.PIXIV, "old")))
            repository.follow(unavailable, emptyList())
            val adapter = object : SourceAdapter by TestAdapter(SourceKey.PIXIV), CreatorPostsSourceAdapter {
                override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> {
                    delay(100)
                    return Page(listOf(testPost(SourceKey.PIXIV, "new")), null)
                }
            }
            val owner = CreatorFollowsViewModel(settings, TestRegistry(listOf(adapter)))
            store.put("follows", owner)
            owner.refresh()
            runCurrent()
            assertTrue(owner.refreshing.value)
            advanceUntilIdle()
            assertFalse(owner.refreshing.value)
            assertEquals(1, owner.errors.value.size)
            assertEquals(1, repository.observe().first().first().newPostCount)
            owner.unfollow(available)
            advanceUntilIdle()
            assertEquals(1, owner.follows.value.size)
            owner.toggle(available, emptyList())
            advanceUntilIdle()
            assertEquals(2, owner.follows.value.size)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `cancelling manual checks keeps previous durable results`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val settings = InMemorySettingsRepository()
            val creator = CreatorProfile(SourceKey.PIXIV, "Artist", "1", uploadsQuery = "user:1")
            val repository = CreatorFollowsRepository(settings)
            repository.follow(creator, emptyList())
            val adapter = object : SourceAdapter by TestAdapter(SourceKey.PIXIV), CreatorPostsSourceAdapter {
                override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> {
                    delay(30_000)
                    return Page(emptyList(), null)
                }
            }
            val owner = CreatorFollowsViewModel(settings, TestRegistry(listOf(adapter)))
            store.put("follows", owner)
            owner.refresh()
            runCurrent()
            owner.cancelRefresh()
            runCurrent()
            assertFalse(owner.refreshing.value)
            assertTrue(owner.errors.value.isEmpty())
            assertEquals(null, repository.observe().first().single().checkedAtEpochMs)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
