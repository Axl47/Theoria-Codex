package com.theoriacodex.app.ui.components

import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.repository.UiRestoreRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedFabRestoreRegistryTest {
    @Test
    fun `a failed earlier write cannot roll back a newer edit and the latest failure restores committed state`() = runTest {
        val stored = InMemoryUiRestoreRepository()
        val committed = FeedFabRestoreState(hideLiked = true)
        stored.setFeedFabRestoreState(SEARCH_FEED_FAB_CONTEXT, committed)
        val release = CompletableDeferred<Unit>()
        var attempt = 0
        val repository = object : UiRestoreRepository by stored {
            override suspend fun setFeedFabRestoreState(contextKey: String, state: FeedFabRestoreState) {
                attempt++
                if (attempt == 1) { release.await(); error("first write failed") }
                if (attempt == 3) error("latest write failed")
                stored.setFeedFabRestoreState(contextKey, state)
            }
        }
        val owner = FeedFabRestoreRegistry(repository, this)
        owner.load()
        owner.update(SEARCH_FEED_FAB_CONTEXT, FeedFabRestoreState(animatedOnly = true))
        runCurrent()
        val latest = FeedFabRestoreState(hideSaved = true)
        owner.update(SEARCH_FEED_FAB_CONTEXT, latest)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(latest, owner.state(SEARCH_FEED_FAB_CONTEXT))
        assertEquals(latest, stored.getFeedFabRestoreStates()[SEARCH_FEED_FAB_CONTEXT])
        owner.update(SEARCH_FEED_FAB_CONTEXT, FeedFabRestoreState(hideWatched = true))
        advanceUntilIdle()
        assertEquals(latest, owner.state(SEARCH_FEED_FAB_CONTEXT))
        assertEquals(2, owner.feedback.notices.value.size)
    }

    @Test
    fun `loaded contexts update immediately and persist independently`() = runTest {
        val repository = InMemoryUiRestoreRepository().apply {
            setFeedFabRestoreState(SEARCH_FEED_FAB_CONTEXT, FeedFabRestoreState(hideLiked = true))
        }
        val registry = FeedFabRestoreRegistry(repository, this)

        assertNull(registry.state(SEARCH_FEED_FAB_CONTEXT))
        registry.load()
        val forYou = FeedFabRestoreState(animatedOnly = true, sortMode = "TOP")
        registry.update(FOR_YOU_FEED_FAB_CONTEXT, forYou)

        assertEquals(FeedFabRestoreState(hideLiked = true), registry.state(SEARCH_FEED_FAB_CONTEXT))
        assertEquals(forYou, registry.state(FOR_YOU_FEED_FAB_CONTEXT))
        advanceUntilIdle()
        assertEquals(forYou, repository.getFeedFabRestoreStates()[FOR_YOU_FEED_FAB_CONTEXT])
    }

    @Test
    fun `creator and Codex identities produce separate context keys`() {
        assertEquals("creator:PIXIV:42", creatorFeedFabContext("PIXIV", "42"))
        assertEquals("codex:library-a", codexFeedFabContext("library-a"))
    }
}
