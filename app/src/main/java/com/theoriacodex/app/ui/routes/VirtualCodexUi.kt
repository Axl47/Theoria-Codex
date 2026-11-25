package com.theoriacodex.app.ui.routes

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import com.theoriacodex.app.codex.FollowedCodexFilters
import com.theoriacodex.app.codex.FollowedCodexHeader
import com.theoriacodex.app.codex.SavedSearchActions
import com.theoriacodex.app.codex.SavedSearchesCodexFilters
import com.theoriacodex.app.codex.SavedSearchesCodexHeader
import com.theoriacodex.app.ui.components.FeedView
import com.theoriacodex.data.repository.FeedFabRestoreState

internal data class VirtualCodexUi(
    val header: @Composable (FeedView, LazyStaggeredGridState) -> Unit,
    val filters: @Composable (() -> Unit) -> Unit,
    val filtersActive: Boolean,
    val emptyMessage: String,
)

internal fun virtualCodexUi(
    state: CodexDetailDestinationState,
    restore: FeedFabRestoreState,
    onChange: (FeedFabRestoreState) -> Unit,
    onBack: () -> Unit,
    onManageFollowed: () -> Unit,
    onManageSearches: () -> Unit,
    searchActions: SavedSearchActions,
): VirtualCodexUi? {
    state.followedOwner?.let { owner ->
        val filtersActive = restore.followedSources.isNotEmpty() || restore.followedAuthors.isNotEmpty()
        return VirtualCodexUi(
            header = { feed, grid ->
                FollowedCodexHeader(owner, state.follows, feed, state.posts, grid, onBack, onManageFollowed)
            },
            filters = { dismiss -> FollowedCodexFilters(state.follows, restore, onChange, { dismiss(); onManageFollowed() }) },
            filtersActive = filtersActive,
            emptyMessage = when {
                state.follows.isEmpty() -> "No followed creators"
                filtersActive -> "Adjust your filters"
                else -> "No posts"
            },
        )
    }
    state.savedSearchOwner?.let { owner ->
        return VirtualCodexUi(
            header = { feed, grid ->
                SavedSearchesCodexHeader(owner, state.savedSearches, feed, state.posts, grid, onBack, onManageSearches)
            },
            filters = { SavedSearchesCodexFilters(state.savedSearches, restore, onChange, searchActions) },
            filtersActive = state.savedSearches.any { it.id in restore.excludedSavedSearches },
            emptyMessage = when {
                state.savedSearches.isEmpty() -> "No saved searches"
                state.savedSearches.any { it.id in restore.excludedSavedSearches } -> "Include a search in this feed"
                else -> "No posts"
            },
        )
    }
    return null
}
