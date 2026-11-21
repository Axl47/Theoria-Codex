package com.theoriacodex.app.search

import com.theoriacodex.app.related.LikeToggleOutcome
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.data.repository.ViewerStreamSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class RelatedPostsSearchViewModelTest : SearchViewModelTestFixture() {
    @Test
    fun `committed like loads related posts without mutating canonical results and unlike clears shelf`() =
        runTest(mainDispatcherRule.dispatcher) {
            val seed = testPost(source = SourceKey.PIXIV, sourcePostId = "seed")
            val related = testPost(source = SourceKey.PIXIV, sourcePostId = "related")
            val adapter = ViewModelSearchAdapter().apply { resultFactory = { listOf(seed) } }
            val loader = FakeRelatedPostsLoader(listOf(related))
            val viewModel = viewModel(adapter, relatedPostsLoader = loader)
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.AddIncludeTerm(com.theoriacodex.domain.model.SearchTerm("seed")))
            viewModel.onAction(SearchAction.ApplyDraft)
            advanceUntilIdle()

            viewModel.onAction(SearchAction.RelatedLikeCommitted(seed, LikeToggleOutcome.LIKED))
            advanceUntilIdle()

            assertEquals(listOf("seed"), resultIds(viewModel))
            assertEquals(
                listOf("related"),
                (viewModel.state.value.relatedPosts as RelatedPostsUiState.Loaded)
                    .posts.map { post -> post.id.sourcePostId },
            )
            assertEquals(listOf(seed.id), loader.requests)

            val effect = async { viewModel.effects.first() }
            viewModel.onAction(SearchAction.OpenRelatedResult(0, listOf(related)))
            val open = effect.await() as SearchEffect.OpenViewer
            assertEquals(ViewerStreamSource.RELATED, open.context.streamSource)
            assertEquals(false, open.liveSearchBinding)

            viewModel.onAction(SearchAction.RelatedLikeCommitted(seed, LikeToggleOutcome.UNLIKED))
            assertEquals(RelatedPostsUiState.Idle, viewModel.state.value.relatedPosts)
        }

    @Test
    fun `opening a related post closes an open restore so the grid keeps its place on return`() =
        runTest(mainDispatcherRule.dispatcher) {
            val seed = testPost(source = SourceKey.PIXIV, sourcePostId = "seed")
            val related = testPost(source = SourceKey.PIXIV, sourcePostId = "related")
            val adapter = ViewModelSearchAdapter().apply { resultFactory = { listOf(seed) } }
            val viewModel = viewModel(adapter, relatedPostsLoader = FakeRelatedPostsLoader(listOf(related)))
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.AddIncludeTerm(com.theoriacodex.domain.model.SearchTerm("seed")))
            viewModel.onAction(SearchAction.ApplyDraft)
            advanceUntilIdle()
            viewModel.onAction(SearchAction.RelatedLikeCommitted(seed, LikeToggleOutcome.LIKED))
            advanceUntilIdle()
            // Applying the search left its move to the top open, waiting for the grid to acknowledge it.
            assertNotNull(viewModel.openScrollState())

            val effect = async { viewModel.effects.first() }
            viewModel.onAction(SearchAction.OpenRelatedResult(0, listOf(related)))
            effect.await() as SearchEffect.OpenViewer

            assertNull(viewModel.openScrollState())
        }

    private fun SearchViewModel.openScrollState() =
        (state.value.restoration as SearchRestorationUiState.Restored).scrollState
}

private class FakeRelatedPostsLoader(
    private val posts: List<Post>,
) : RelatedPostsLoading {
    val requests = mutableListOf<PostId>()
    override fun supports(source: SourceKey): Boolean = source == SourceKey.PIXIV
    override suspend fun load(seed: PostId): List<Post> {
        requests += seed
        return posts
    }
}
