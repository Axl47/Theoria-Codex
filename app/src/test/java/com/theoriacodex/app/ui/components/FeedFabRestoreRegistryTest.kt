package com.theoriacodex.app.ui.components

import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedFabRestoreRegistryTest {
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
