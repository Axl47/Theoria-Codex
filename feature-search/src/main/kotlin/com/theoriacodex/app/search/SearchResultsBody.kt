package com.theoriacodex.app.search

import com.theoriacodex.app.related.RelatedShelfBinding
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
import com.theoriacodex.uikit.R
import com.theoriacodex.app.source.LocalSourceHealth
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.ui.components.DismissibleFailure
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
    related: RelatedShelfBinding,
    feed: SearchFeed,
    cards: com.theoriacodex.app.ui.components.FeedCardEnvironment,
    gridState: LazyStaggeredGridState,
    pageDemand: FeedPageDemandControl,
    inputFocus: SearchInputFocus,
    resolvePostById: suspend (PostId) -> Post?,
    recoverPostMedia: suspend (Post, ImageRef) -> Post?,
    onAction: (SearchAction) -> Unit,
    openPostActions: (Post) -> Unit,
    composerOpen: Boolean = false,
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
    state.pageFailure?.let { failure ->
        DismissibleFailure(failure) { dismiss ->
            SearchFailureSurface(failure, state.content.statuses, retrySearch, onDismiss = dismiss)
        }
    }
    // Results show the pull indicator for a refresh; this line covers newer searches (the open composer shows its
    // own) and refreshes of an emptied feed.
    val emptied = feed.related.projection.entries.isEmpty()
    if ((state.replacingResults && !composerOpen) || (state.refreshingAppliedSearch && emptied)) SearchRefreshProgress()
    when {
        state.loading && !state.retainsResultsWhileRefreshing -> FeedLoadingState(modifier = tapToClearFocus.fillMaxWidth())
        state.content.error != null && state.pageFailure == null -> {
            SearchFailureSurface(requireNotNull(state.content.error), state.content.statuses, retrySearch, tapToClearFocus)
        }
        feed.related.projection.entries.isEmpty() -> Box(modifier = tapToClearFocus) {
            SearchEmptyResults(state, feed, pageDemand, sourceAuthErrorMessage, outageNotices, retrySearch)
        }
        else -> Column(modifier = tapToClearFocus, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sourceAuthErrorMessage?.let { message ->
                DismissibleFailure(message) { dismiss -> SourceAccountNotice(message, state.content.statuses, dismiss) }
            }
            PullToRefreshBox(
                isRefreshing = state.refreshingAppliedSearch && !state.execution.showingPartialResults,
                onRefresh = retrySearch,
                modifier = Modifier.weight(1f),
            ) {
                SearchResultsGrid(state, related, feed, cards, gridState, pageDemand, inputFocus, resolvePostById, recoverPostMedia, onAction, openPostActions)
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
    // Sources with a notice of their own are left out of both this message and its action.
    val unannounced = remember(state.content.statuses, outageNotices) {
        val announced = outageNotices.flatMap(SourceOutageNotice::sources)
        state.content.statuses.filterNot { it.source in announced }
    }
    val sourceFailureMessage = remember(unannounced) { buildSourceFailureMessage(unannounced) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FeedContinueLoading(pageDemand)
        sourceAuthErrorMessage?.let { SourceAccountNotice(it, state.content.statuses) }
        sourceFailureMessage?.let { failureMessage -> SourceFailureBlock(failureMessage, unannounced, onRetry = onRetry) }
        if (feed.durationMetadata.readiness.isResolving) {
            EmptyBlock(messageOverride = "Resolving durations…")
        } else if (!state.content.hasExecutedSearch && !feed.animatedFilterActive) {
            SearchStartSplash(modifier = Modifier.fillMaxWidth())
        } else {
            EmptyBlock(
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
    related: RelatedShelfBinding,
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
        footerMessage = searchFooterMessage(state, feed.durationMetadata.readiness.isResolving, feed.visibleResults.isNotEmpty()),
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
            FeedRelatedPostsShelf(related, feed.related, cards, openPostActions, recoverPostMedia)
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
                text = "Add tags to start searching.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

