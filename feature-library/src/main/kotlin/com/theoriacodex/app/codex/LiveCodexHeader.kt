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
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.feed.FeedPageDemandInput
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.ui.components.DismissibleFailure
import com.theoriacodex.app.ui.components.FeedContinueLoading
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
import com.theoriacodex.app.ui.components.rememberFeedPageDemand
import com.theoriacodex.domain.model.Post

@Composable
internal fun LiveCodexHeader(
    title: String,
    contextKey: String,
    owner: LiveCodexFeedOwner,
    labels: Map<String, String>,
    visiblePosts: List<Post>,
    posts: List<Post>,
    filters: CodexCollectionFilters,
    durationsBlockPaging: Boolean,
    gridState: LazyStaggeredGridState,
    onBack: () -> Unit,
    manageLabel: String,
    onManage: () -> Unit,
) {
    val state = owner.state.collectAsStateWithLifecycle().value
    SecondaryScreenAppBar(title = title, subtitle = "${visiblePosts.size} posts", onBack = onBack,
        onTitleLongPress = onManage) {
        TextButton(onClick = onManage) { Text(manageLabel) }
        IconButton(enabled = !state.loading, onClick = owner::refresh) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh $title")
        }
    }
    val canonicalIndices = remember(posts, visiblePosts) {
        val byId = posts.mapIndexed { index, post -> post.id to index }.toMap()
        visiblePosts.map { byId.getValue(it.id) }
    }
    val demand = rememberFeedPageDemand(
        input = FeedPageDemandInput(
            contextKey = contextKey, completedGeneration = state.generation,
            canonicalCount = posts.size, visibleCount = visiblePosts.size,
            canLoadMore = state.canLoadMore, paging = state.loading, durationsBlockPaging = durationsBlockPaging,
        ),
        filters = SearchVisibilityFilters(animatedOnly = filters.animatedOnly, animatedDurationRange = filters.animatedDurationRange),
        gridState = gridState,
        canonicalIndexForVisibleItems = { indices -> indices.mapNotNull(canonicalIndices::getOrNull).maxOrNull() },
        onLoadNextPage = owner::loadMore,
    )
    LiveCodexFailures(state.errors, labels, title, manageLabel, retryEnabled = !state.loading, onRetry = owner::retry)
    FeedContinueLoading(demand)
}

/** Which followed or saved branches failed, beside the posts that did load; Dismiss hides them until they change. */
@Composable
fun LiveCodexFailures(
    errors: Map<String, String>,
    labels: Map<String, String>,
    title: String,
    manageLabel: String,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
) {
    if (errors.isEmpty()) return
    DismissibleFailure(errors) { dismiss ->
        Text(errors.entries.take(2).joinToString("\n") { (id, error) -> "${labels[id] ?: title}: $error" } +
            if (errors.size > 2) "\n${errors.size - 2} more could not load" else "")
        Row {
            TextButton(onClick = dismiss) { Text("Dismiss") }
            TextButton(enabled = retryEnabled, onClick = onRetry) { Text("Retry failed ${manageLabel.lowercase()}") }
        }
    }
}
