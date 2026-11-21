package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.feed.FeedPageDemandInput
import com.theoriacodex.app.ui.components.DismissibleFailure
import com.theoriacodex.app.ui.components.FeedContinueLoading
import com.theoriacodex.app.ui.components.FeedView
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
import com.theoriacodex.app.ui.components.rememberFeedPageDemand
import com.theoriacodex.domain.model.Post

/**
 * A live Saved feed's app bar (Followed, Searches) over its paging and failures. [branches] names what the feed
 * merges, as its failures' Retry says it; [manageLabel] is the button that opens their management, which a long press
 * on the title also opens.
 */
@Composable
internal fun LiveCodexHeader(
    title: String,
    contextKey: String,
    owner: LiveCodexFeedOwner,
    labels: Map<String, String>,
    feed: FeedView,
    posts: List<Post>,
    gridState: LazyStaggeredGridState,
    onBack: () -> Unit,
    branches: String,
    manageLabel: String,
    onManage: () -> Unit,
) {
    val state = owner.state.collectAsStateWithLifecycle().value
    SecondaryScreenAppBar(title = title, subtitle = "${feed.visiblePosts.size} posts", onBack = onBack,
        onTitleLongPress = onManage) {
        TextButton(onClick = onManage) { Text(manageLabel) }
        IconButton(enabled = !state.loading, onClick = owner::refresh) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh $title")
        }
    }
    // The grid shows the feed's projection, which places an open related shelf among the posts.
    val demand = rememberFeedPageDemand(
        input = FeedPageDemandInput(
            contextKey = contextKey, completedGeneration = state.generation,
            canonicalCount = posts.size, visibleCount = feed.visiblePosts.size,
            canLoadMore = state.canLoadMore, paging = state.loading, durationsBlockPaging = feed.durations.blocksPaging,
        ),
        filters = feed.filters,
        gridState = gridState,
        projection = feed.related.projection,
        onLoadNextPage = owner::loadMore,
    )
    LiveCodexFailures(state.errors, labels, title, branches, retryEnabled = !state.loading, onRetry = owner::retry)
    FeedContinueLoading(demand)
}

/** Which followed or saved branches failed, beside the posts that did load; Dismiss hides them until they change. */
@Composable
fun LiveCodexFailures(
    errors: Map<String, String>,
    labels: Map<String, String>,
    title: String,
    branches: String,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
) {
    if (errors.isEmpty()) return
    DismissibleFailure(errors) { dismiss ->
        Text(errors.entries.take(2).joinToString("\n") { (id, error) -> "${labels[id] ?: title}: $error" } +
            if (errors.size > 2) "\n${errors.size - 2} more could not load" else "")
        Row {
            TextButton(onClick = dismiss) { Text("Dismiss") }
            TextButton(enabled = retryEnabled, onClick = onRetry) { Text("Retry failed ${branches.lowercase()}") }
        }
    }
}
