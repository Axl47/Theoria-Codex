package com.theoriacodex.app.recents

import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.related.LikeToggleOutcome
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecentsRelatedViewModelTest {
    @Test fun `a like opens the shelf in the shown view and switching views closes it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val first = testPost(sourcePostId = "first")
            val liked = testPost(sourcePostId = "liked")
            val related = listOf(testPost(sourcePostId = "related"))
            val owner = RecentsRelatedViewModel(FixedRelatedLoader(related))
            store.put("owner", owner)

            owner.showing("WATCHED:null", listOf(first, liked))
            owner.related.onLikeCommitted(liked, LikeToggleOutcome.LIKED)
            advanceUntilIdle()
            val loaded = owner.state.value as RelatedPostsUiState.Loaded
            assertEquals(1, loaded.request.anchorCanonicalIndex)
            assertEquals(related, loaded.posts)

            // The same view with a newly watched post keeps the shelf.
            owner.showing("WATCHED:null", listOf(testPost(sourcePostId = "new"), first, liked))
            assertEquals(loaded, owner.state.value)

            owner.showing("ALL:null", listOf(first, liked))
            assertEquals(RelatedPostsUiState.Idle, owner.state.value)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    private class FixedRelatedLoader(private val posts: List<Post>) : RelatedPostsLoading {
        override fun supports(source: SourceKey) = true
        override suspend fun load(seed: PostId) = posts
    }
}
