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
