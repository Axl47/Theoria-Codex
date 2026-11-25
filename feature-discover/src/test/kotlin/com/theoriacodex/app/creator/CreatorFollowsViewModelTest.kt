package com.theoriacodex.app.creator

import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.search.TestAdapter
import com.theoriacodex.app.search.TestRegistry
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.undo.UndoSlot
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CreatorFollowsRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.SettingsRepository
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

    @Test fun `a refresh of 60 follows records each batch of 25 checks before checking the next (ADR 0019)`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val settings = InMemorySettingsRepository()
            val repository = CreatorFollowsRepository(settings)
            repeat(60) { repository.follow(CreatorProfile(SourceKey.PIXIV, "C$it", "$it", uploadsQuery = "user:$it"), emptyList()) }
            val events = mutableListOf<String>()
            val counting = object : SettingsRepository by settings {
                override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
                    settings.updateSettings { current ->
                        transform(current).also { next ->
                            val checked = { s: AppSettings -> s.followedCreators.count { it.checkedAtEpochMs != null } }
                            if (checked(next) != checked(current)) events += "write"
                        }
                    }
                }
            }
            val adapter = object : SourceAdapter by TestAdapter(SourceKey.PIXIV), CreatorPostsSourceAdapter {
                override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> {
                    events += "check"
                    return Page(emptyList(), null)
                }
            }
            val owner = CreatorFollowsViewModel(counting, TestRegistry(listOf(adapter)))
            store.put("follows", owner)
            owner.refresh()
            advanceUntilIdle()
            assertEquals(listOf(25, 25, 10).flatMap { List(it) { "check" } + "write" }, events)
            assertTrue(repository.observe().first().all { it.checkedAtEpochMs != null })
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `undoing an unfollow restores the same record in its place (ADR 0019)`() = runTest {
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
            runCurrent()
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

    @Test fun `leaving the app cancels the automatic check but not one the user started (ADR 0019)`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val settings = InMemorySettingsRepository()
            val creator = CreatorProfile(SourceKey.PIXIV, "Artist", "1", uploadsQuery = "user:1")
            val repository = CreatorFollowsRepository(settings)
            repository.follow(creator, emptyList())
            val adapter = object : SourceAdapter by TestAdapter(SourceKey.PIXIV), CreatorPostsSourceAdapter {
                override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> {
                    delay(5_000)
                    return Page(emptyList(), null)
                }
            }
            val owner = CreatorFollowsViewModel(settings, TestRegistry(listOf(adapter)))
            store.put("follows", owner)
            owner.refreshIfStale(0L)
            runCurrent()
            owner.refresh()
            owner.cancelAutomaticRefresh()
            runCurrent()
            assertTrue(owner.refreshing.value)
            advanceUntilIdle()
            assertFalse(owner.refreshing.value)
            assertTrue(repository.observe().first().single().checkedAtEpochMs != null)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `an automatic check cancelled by leaving the app keeps durable results and is not sent again within the interval (ADR 0019)`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val settings = InMemorySettingsRepository()
            val creator = CreatorProfile(SourceKey.PIXIV, "Artist", "1", uploadsQuery = "user:1")
            val repository = CreatorFollowsRepository(settings)
            repository.follow(creator, emptyList())
            var calls = 0
            val adapter = object : SourceAdapter by TestAdapter(SourceKey.PIXIV), CreatorPostsSourceAdapter {
                override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> {
                    calls++
                    delay(30_000)
                    return Page(emptyList(), null)
                }
            }
            val owner = CreatorFollowsViewModel(settings, TestRegistry(listOf(adapter)))
            store.put("follows", owner)
            owner.refreshIfStale(0L)
            runCurrent()
            owner.cancelAutomaticRefresh()
            runCurrent()
            assertFalse(owner.refreshing.value)
            assertTrue(owner.errors.value.isEmpty())
            assertEquals(null, repository.observe().first().single().checkedAtEpochMs)
            owner.refreshIfStale(60 * 60 * 1000L)
            runCurrent()
            assertEquals(1, calls)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `follows a cancelled automatic check never started stay due for the next foreground`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val settings = InMemorySettingsRepository()
            val repository = CreatorFollowsRepository(settings)
            (1..3).forEach { repository.follow(CreatorProfile(SourceKey.PIXIV, "Artist $it", "$it", uploadsQuery = "user:$it"), emptyList()) }
            val asked = mutableListOf<String?>()
            val adapter = object : SourceAdapter by TestAdapter(SourceKey.PIXIV), CreatorPostsSourceAdapter {
                override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> {
                    asked += creator.profileId
                    delay(30_000)
                    return Page(emptyList(), null)
                }
            }
            val owner = CreatorFollowsViewModel(settings, TestRegistry(listOf(adapter)))
            store.put("follows", owner)
            owner.refreshIfStale(0L)
            runCurrent()
            owner.cancelAutomaticRefresh()
            runCurrent()
            owner.refreshIfStale(60 * 60 * 1000L)
            runCurrent()
            assertEquals(listOf("1", "2", "3"), asked)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `creators checked before leaving the app keep their results`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val settings = InMemorySettingsRepository()
            val repository = CreatorFollowsRepository(settings)
            (1..2).forEach { repository.follow(CreatorProfile(SourceKey.PIXIV, "Artist $it", "$it", uploadsQuery = "user:$it"), emptyList()) }
            val adapter = object : SourceAdapter by TestAdapter(SourceKey.PIXIV), CreatorPostsSourceAdapter {
                override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> {
                    if (creator.profileId == "2") delay(30_000)
                    return Page(listOf(testPost(SourceKey.PIXIV, "new-${creator.profileId}")), null)
                }
            }
            val owner = CreatorFollowsViewModel(settings, TestRegistry(listOf(adapter)))
            store.put("follows", owner)
            owner.refreshIfStale(0L)
            runCurrent()
            owner.cancelAutomaticRefresh()
            runCurrent()
            val follows = repository.observe().first().associateBy { it.creator.profileId }
            assertEquals(0L, follows.getValue("1").checkedAtEpochMs)
            assertEquals(null, follows.getValue("2").checkedAtEpochMs)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `app-open checks wait out the interval even after a failure (ADR 0019)`() = runTest {
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
            val restarted = CreatorFollowsViewModel(settings, TestRegistry(listOf(adapter)))
            store.put("restarted", restarted)
            restarted.refreshIfStale(3 * hour + hour / 2)
            advanceUntilIdle()
            assertEquals(2, calls)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `an automatic check skips follows checked within the interval but manual refresh does not`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val settings = InMemorySettingsRepository()
            val repository = CreatorFollowsRepository(settings)
            val done = CreatorProfile(SourceKey.PIXIV, "Done", "1", uploadsQuery = "user:1")
            val unfinished = CreatorProfile(SourceKey.PIXIV, "Unfinished", "2", uploadsQuery = "user:2")
            listOf(done, unfinished).forEach { repository.follow(it, emptyList()) }
            val hour = 60 * 60 * 1000L
            repository.recordChecks(mapOf(repository.observe().first().first().membershipId to emptyList()), 2 * hour)
            val checked = mutableListOf<String>()
            val adapter = object : SourceAdapter by TestAdapter(SourceKey.PIXIV), CreatorPostsSourceAdapter {
                override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> {
                    checked += creator.displayName
                    return Page(emptyList(), null)
                }
            }
            val owner = CreatorFollowsViewModel(settings, TestRegistry(listOf(adapter)))
            store.put("follows", owner)
            owner.refreshIfStale(3 * hour)
            advanceUntilIdle()
            assertEquals(listOf("Unfinished"), checked)
            checked.clear()
            owner.refresh()
            advanceUntilIdle()
            assertEquals(listOf("Done", "Unfinished"), checked.sorted())
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `a manual refresh during the automatic check also checks the follows it skipped (ADR 0019)`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val settings = InMemorySettingsRepository()
            val repository = CreatorFollowsRepository(settings)
            val done = CreatorProfile(SourceKey.PIXIV, "Done", "1", uploadsQuery = "user:1")
            val stale = CreatorProfile(SourceKey.PIXIV, "Stale", "2", uploadsQuery = "user:2")
            listOf(done, stale).forEach { repository.follow(it, emptyList()) }
            val hour = 60 * 60 * 1000L
            repository.recordChecks(mapOf(repository.observe().first().first().membershipId to emptyList()), 2 * hour)
            val checked = mutableListOf<String>()
            val adapter = object : SourceAdapter by TestAdapter(SourceKey.PIXIV), CreatorPostsSourceAdapter {
                override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> {
                    checked += creator.displayName
                    delay(5_000)
                    return Page(emptyList(), null)
                }
            }
            val owner = CreatorFollowsViewModel(settings, TestRegistry(listOf(adapter)))
            store.put("follows", owner)
            owner.refreshIfStale(3 * hour)
            runCurrent()
            assertEquals(listOf("Stale"), checked)
            owner.refresh()
            advanceUntilIdle()
            assertEquals(listOf("Done", "Stale"), checked.drop(1).sorted())
            assertFalse(owner.refreshing.value)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
