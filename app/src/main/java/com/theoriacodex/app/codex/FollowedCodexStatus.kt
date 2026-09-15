package com.theoriacodex.app.codex

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
import com.theoriacodex.app.feed.FeedPageDemandInput
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.components.FeedContinueLoading
import com.theoriacodex.app.ui.components.rememberFeedPageDemand
import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.domain.model.Post

@Composable
internal fun FollowedCodexStatus(
    owner: FollowedCodexViewModel,
    state: FollowedFeedState,
    follows: List<FollowedCreator>,
    visiblePosts: List<Post>,
    posts: List<Post>,
    filters: CodexCollectionFilters,
    resolvingDurations: Boolean,
    gridState: LazyStaggeredGridState,
) {
    val canonicalIndices = remember(posts, visiblePosts) {
        val byId = posts.mapIndexed { index, post -> post.id to index }.toMap()
        visiblePosts.map { byId.getValue(it.id) }
    }
    val demand = rememberFeedPageDemand(
        input = FeedPageDemandInput(
            contextKey = FOLLOWED_CODEX_ID, completedGeneration = state.generation,
            canonicalCount = posts.size, visibleCount = visiblePosts.size,
            canLoadMore = state.canLoadMore, paging = state.loading,
            resolvingDurations = resolvingDurations,
        ),
        filters = SearchVisibilityFilters(animatedOnly = filters.animatedOnly,
            animatedDurationRange = filters.animatedDurationRange),
        gridState = gridState,
        canonicalIndexForVisibleItems = { indices -> indices.mapNotNull(canonicalIndices::getOrNull).maxOrNull() },
        onLoadNextPage = owner::loadMore,
    )
    if (state.loading) Text("Loading followed posts…")
    if (state.errors.isNotEmpty()) {
        val failed = follows.filter { it.membershipId in state.errors }
        Text(failed.take(2).joinToString("\n") {
            "${it.creator.displayName} · ${it.creator.source.displayName()}: ${state.errors[it.membershipId]}"
        } + if (failed.size > 2) "\n${failed.size - 2} more authors could not load" else "")
        TextButton(enabled = !state.loading, onClick = owner::retry) { Text("Retry failed authors") }
    }
    FeedContinueLoading(demand)
}

@Composable
internal fun FollowedCodexHeader(
    owner: FollowedCodexViewModel,
    follows: List<FollowedCreator>,
    visiblePosts: List<Post>,
    posts: List<Post>,
    filters: CodexCollectionFilters,
    resolvingDurations: Boolean,
    gridState: LazyStaggeredGridState,
    onBack: () -> Unit,
    onManage: () -> Unit,
) {
    val feed = owner.state.collectAsStateWithLifecycle().value
    SecondaryScreenAppBar(title = "Followed", subtitle = "${visiblePosts.size} posts", onBack = onBack) {
        TextButton(onClick = onManage) { Text("Authors") }
        TextButton(enabled = !feed.loading, onClick = owner::refresh) { Text("Refresh") }
    }
    FollowedCodexStatus(owner, feed, follows, visiblePosts, posts, filters, resolvingDurations, gridState)
}
