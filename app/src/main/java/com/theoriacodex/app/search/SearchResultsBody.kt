package com.theoriacodex.app.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.R
import com.theoriacodex.app.source.LocalSourceHealth
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.ui.components.FeedContinueLoading
import com.theoriacodex.app.ui.components.FeedLoadingState
import com.theoriacodex.app.ui.components.FeedPageDemandControl
import com.theoriacodex.app.ui.components.FeedPostCard
import com.theoriacodex.app.ui.components.FeedRelatedPostsShelf
import com.theoriacodex.app.ui.components.TwoColumnProjectedPostStaggeredGrid
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

@Composable
internal fun ColumnScope.SearchResultsBody(
    state: SearchUiState,
    feed: SearchFeed,
    cards: com.theoriacodex.app.ui.components.FeedCardEnvironment,
    gridState: LazyStaggeredGridState,
    pageDemand: FeedPageDemandControl,
    inputFocus: SearchInputFocus,
    resolvePostById: suspend (PostId) -> Post?,
    recoverPostMedia: suspend (Post, ImageRef) -> Post?,
    onAction: (SearchAction) -> Unit,
    openPostActions: (Post) -> Unit,
) {
    val clearFocusInteraction = remember { MutableInteractionSource() }
    val tapToClearFocus = Modifier
        .weight(1f)
        .clickable(interactionSource = clearFocusInteraction, indication = null) { inputFocus.clear() }
    val sourceAuthErrorMessage = remember(state.content.statuses) { buildSourceAuthErrorMessage(state.content.statuses) }
    val retrySearch = { inputFocus.clear(); onAction(SearchAction.Retry) }
    val issues = LocalSourceHealth.current.issues
    val scope = state.query.appliedSourceScope
    val outageNotices = remember(state.content.statuses, issues, scope) {
        // A single source already says so in its own error, whose action verifies or retries it.
        if (scope is SearchSourceScope.Single) emptyList() else sourceOutageNotices(state.content.statuses, issues)
    }
    SourceOutageNotices(outageNotices)
    state.pageFailure?.let { SearchFailureSurface(it, state.content.statuses, retrySearch) }
    // Results show the pull indicator instead; this keeps refreshes of an emptied feed visible.
    if (state.retainsResultsWhileRefreshing && feed.related.projection.entries.isEmpty()) SearchRefreshProgress()
    when {
        state.loading && !state.retainsResultsWhileRefreshing -> FeedLoadingState(modifier = tapToClearFocus.fillMaxWidth())
        state.content.error != null && state.pageFailure == null -> {
            SearchFailureSurface(requireNotNull(state.content.error), state.content.statuses, retrySearch, tapToClearFocus)
        }
        feed.related.projection.entries.isEmpty() -> Box(modifier = tapToClearFocus) {
            SearchEmptyResults(state, feed, pageDemand, sourceAuthErrorMessage, outageNotices, retrySearch)
        }
        else -> Column(modifier = tapToClearFocus, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sourceAuthErrorMessage?.let { SourceAccountNotice(it, state.content.statuses) }
            PullToRefreshBox(
                isRefreshing = state.retainsResultsWhileRefreshing && !state.execution.showingPartialResults,
                onRefresh = retrySearch,
                modifier = Modifier.weight(1f),
            ) {
                SearchResultsGrid(state, feed, cards, gridState, pageDemand, inputFocus, resolvePostById, recoverPostMedia, onAction, openPostActions)
            }
        }
    }
}

@Composable
private fun SearchEmptyResults(
    state: SearchUiState,
    feed: SearchFeed,
    pageDemand: FeedPageDemandControl,
    sourceAuthErrorMessage: String?,
    outageNotices: List<SourceOutageNotice>,
    onRetry: () -> Unit,
) {
    val sourceFailureMessage = remember(state.content.statuses, outageNotices) {
        val announced = outageNotices.flatMap(SourceOutageNotice::sources)
        buildSourceFailureMessage(state.content.statuses.filterNot { it.source in announced })
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FeedContinueLoading(pageDemand)
        sourceAuthErrorMessage?.let { SourceAccountNotice(it, state.content.statuses) }
        sourceFailureMessage?.let { failureMessage -> SourceFailureBlock(failureMessage, state.content.statuses, onRetry) }
        if (feed.durationMetadata.readiness.isResolving) {
            EmptyBlock(hasPendingChanges = false, messageOverride = "Resolving durations…")
        } else if (!state.content.hasExecutedSearch && !feed.animatedFilterActive) {
            SearchStartSplash(modifier = Modifier.fillMaxWidth())
        } else {
            EmptyBlock(
                hasPendingChanges = state.hasPendingChanges,
                messageOverride = buildEmptySearchMessage(
                    sourceResults = state.content.results,
                    visibilityFilters = feed.visibilityFilters,
                    loadingMore = state.loadingMore,
                    canLoadMore = state.content.canLoadMore,
                ),
            )
        }
    }
}

@Composable
private fun SearchResultsGrid(
    state: SearchUiState,
    feed: SearchFeed,
    cards: com.theoriacodex.app.ui.components.FeedCardEnvironment,
    gridState: LazyStaggeredGridState,
    pageDemand: FeedPageDemandControl,
    inputFocus: SearchInputFocus,
    resolvePostById: suspend (PostId) -> Post?,
    recoverPostMedia: suspend (Post, ImageRef) -> Post?,
    onAction: (SearchAction) -> Unit,
    openPostActions: (Post) -> Unit,
) {
    // Cards capture this rather than the whole state, which changes on every keystroke and page.
    val showSourceBadge = state.query.appliedSourceScope !is SearchSourceScope.Single
    val relatedPosts = state.relatedPosts
    // Read at tap time: the filtered list is rebuilt on every like, and cards must not recompose for it.
    val currentFeed by rememberUpdatedState(feed)
    TwoColumnProjectedPostStaggeredGrid(
        projection = feed.related.projection,
        state = gridState,
        modifier = Modifier.fillMaxSize().testTag("Search results"),
        showPagingTile = state.loadingMore,
        footerContent = if (pageDemand.showContinue) {
            { FeedContinueLoading(pageDemand) }
        } else {
            null
        },
        footerMessage = if (feed.durationMetadata.readiness.isResolving) "Resolving durations…" else null,
        postContent = { _, post ->
            FeedPostCard(
                post = post,
                environment = cards,
                showWatchHistory = true,
                showSourceBadge = showSourceBadge,
                resolvePostById = resolvePostById,
                recoverPostMedia = recoverPostMedia,
                onClick = {
                    inputFocus.clear()
                    onAction(SearchAction.OpenResult(
                        postId = post.id,
                        visibleResults = currentFeed.visibleResults,
                        scrollOffsetHint = gridState.firstVisibleItemScrollOffset,
                        visibilityFilters = currentFeed.visibilityFilters,
                    ))
                },
                onLongPress = { openPostActions(post) },
            )
        },
        shelfContent = {
            FeedRelatedPostsShelf(
                state = relatedPosts,
                presentation = feed.related,
                environment = cards,
                recoverPostMedia = recoverPostMedia,
                onOpenPost = { posts, index -> onAction(SearchAction.OpenRelatedResult(index, posts)) },
                onLongPress = openPostActions,
                onDismiss = { onAction(SearchAction.DismissRelatedPosts) },
                onRetry = { onAction(SearchAction.RetryRelatedPosts) },
                onPreviousPage = { onAction(SearchAction.ShowPreviousRelatedPosts) },
                onNextPage = { onAction(SearchAction.ShowNextRelatedPosts) },
            )
        },
    )
}

@Composable
private fun SearchStartSplash(
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.theoria_splash_mark),
                contentDescription = "Theoria splash",
                modifier = Modifier.size(180.dp),
            )
            Text(
                text = "Add tags and press Apply to start searching.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

