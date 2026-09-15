package com.theoriacodex.app.related

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelatedPostsRouteControllerTest {
    @Test
    fun `overall deadline replaces loading with retryable failure`() = runTest {
        val seed = testPost(sourcePostId = "seed")
        var state: RelatedPostsUiState = RelatedPostsUiState.Idle
        val controller = controller(
            seed = seed,
            state = { state },
            updateState = { state = it },
            loader = SuspendingRelatedLoader,
            timeoutMs = 100L,
        )

        controller.onLikeCommitted(seed, LikeToggleOutcome.LIKED)
        runCurrent()
        assertTrue(state is RelatedPostsUiState.Loading)

        advanceTimeBy(100L)
        runCurrent()

        assertEquals("Related posts timed out. Try again.", (state as RelatedPostsUiState.Failed).message)
    }

    @Test
    fun `owner cancellation clears without becoming timeout failure`() = runTest {
        val seed = testPost(sourcePostId = "seed")
        var state: RelatedPostsUiState = RelatedPostsUiState.Idle
        val controller = controller(
            seed = seed,
            state = { state },
            updateState = { state = it },
            loader = SuspendingRelatedLoader,
            timeoutMs = 100L,
        )

        controller.onLikeCommitted(seed, LikeToggleOutcome.LIKED)
        runCurrent()
        controller.clear()
        advanceTimeBy(100L)
        runCurrent()

        assertEquals(RelatedPostsUiState.Idle, state)
    }

    @Test
    fun `liking a shelf post promotes it and opens a retained next page`() = runTest {
        val seed = testPost(sourcePostId = "seed")
        val promoted = testPost(sourcePostId = "promoted")
        val sibling = testPost(sourcePostId = "sibling")
        val next = testPost(sourcePostId = "next")
        var state: RelatedPostsUiState = RelatedPostsUiState.Idle
        var canonicalPosts = listOf(seed, testPost(sourcePostId = "tail"))
        val loader = SequencedRelatedLoader(
            mapOf(seed.id to listOf(promoted, sibling), promoted.id to listOf(next)),
        )
        val controller = RelatedPostsRouteController(
            scope = this,
            loader = loader,
            currentState = { state },
            canonicalPosts = { canonicalPosts },
            promotePost = { post, insertionIndex ->
                canonicalPosts = promoteRelatedPost(canonicalPosts, post, insertionIndex)
            },
            updateState = { state = it },
        )

        controller.onLikeCommitted(seed, LikeToggleOutcome.LIKED)
        runCurrent()
        controller.onLikeCommitted(promoted, LikeToggleOutcome.LIKED)
        runCurrent()

        assertEquals(listOf("seed", "promoted", "tail"), canonicalPosts.map { it.id.sourcePostId })
        assertEquals(listOf(seed.id, promoted.id), loader.requests)
        assertEquals(listOf("next"), state.loadedPosts.map { it.id.sourcePostId })
        assertEquals(1, state.currentPageIndex)

        controller.showPreviousPage()
        assertEquals(listOf("sibling"), state.loadedPosts.map { it.id.sourcePostId })
        controller.showNextPage()
        assertEquals(listOf("next"), state.loadedPosts.map { it.id.sourcePostId })
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        seed: Post,
        state: () -> RelatedPostsUiState,
        updateState: (RelatedPostsUiState) -> Unit,
        loader: RelatedPostsLoading,
        timeoutMs: Long,
    ): RelatedPostsRouteController = RelatedPostsRouteController(
        scope = this,
        loader = loader,
        currentState = state,
        canonicalPosts = { listOf(seed) },
        updateState = updateState,
        requestTimeoutMs = timeoutMs,
    )
}

private object SuspendingRelatedLoader : RelatedPostsLoading {
    override fun supports(source: SourceKey): Boolean = true
    override suspend fun load(seed: PostId): List<Post> = awaitCancellation()
}

private class SequencedRelatedLoader(
    private val results: Map<PostId, List<Post>>,
) : RelatedPostsLoading {
    val requests = mutableListOf<PostId>()
    override fun supports(source: SourceKey): Boolean = true
    override suspend fun load(seed: PostId): List<Post> {
        requests += seed
        return results[seed].orEmpty()
    }
}
