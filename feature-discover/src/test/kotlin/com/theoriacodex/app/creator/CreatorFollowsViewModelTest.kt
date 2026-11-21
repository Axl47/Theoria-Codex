package com.theoriacodex.app.creator

import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.search.TestAdapter
import com.theoriacodex.app.search.TestRegistry
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.undo.UndoSlot
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
            owner.unfollow(available, UndoSlot())
            advanceUntilIdle()
            assertEquals(1, owner.follows.value.size)
            owner.toggle(available, emptyList(), UndoSlot())
            advanceUntilIdle()
            assertEquals(2, owner.follows.value.size)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `undoing an unfollow restores the same record in its place`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val settings = InMemorySettingsRepository()
            val repository = CreatorFollowsRepository(settings)
            val first = CreatorProfile(SourceKey.PIXIV, "One", "1", uploadsQuery = "user:1")
            val second = CreatorProfile(SourceKey.PIXIV, "Two", "2", uploadsQuery = "user:2")
            repository.follow(first, listOf(testPost(SourceKey.PIXIV, "seen")))
            repository.follow(second, emptyList())
            val before = repository.observe().first()
            val owner = CreatorFollowsViewModel(settings, TestRegistry(emptyList()))
            store.put("follows", owner)
            val undo = UndoSlot()
            owner.toggle(first, listOf(testPost(SourceKey.PIXIV, "visible")), undo)
            runCurrent() // advancing time would expire the offer
            val offer = requireNotNull(undo.offer.value)
            assertEquals(CreatorFollowAnchors.page(first), offer.anchor)
            assertEquals(first, owner.unfollowed.value?.follow?.creator)
            assertEquals(listOf(second), repository.observe().first().map { it.creator })

            undo.act(offer)
            advanceUntilIdle()
            assertEquals(before, repository.observe().first())
            assertEquals(null, owner.unfollowed.value)
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

    @Test fun `app-open checks wait out the interval even after a failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val settings = InMemorySettingsRepository()
            CreatorFollowsRepository(settings).follow(CreatorProfile(SourceKey.PIXIV, "Artist", "1", uploadsQuery = "user:1"), emptyList())
            var calls = 0
            val adapter = object : SourceAdapter by TestAdapter(SourceKey.PIXIV), CreatorPostsSourceAdapter {
                override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> {
                    calls++
                    error("offline")
                }
            }
            val owner = CreatorFollowsViewModel(settings, TestRegistry(listOf(adapter)))
            store.put("follows", owner)
            val hour = 60 * 60 * 1000L
            listOf(0L, hour, 2 * hour, 3 * hour).forEach { now ->
                owner.refreshIfStale(now)
                advanceUntilIdle()
            }
            assertEquals(2, calls)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
