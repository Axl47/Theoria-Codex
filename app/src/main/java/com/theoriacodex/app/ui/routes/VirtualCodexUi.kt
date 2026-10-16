package com.theoriacodex.app.ui.routes

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import com.theoriacodex.app.codex.CodexCollectionFilters
import com.theoriacodex.app.codex.FollowedCodexFilters
import com.theoriacodex.app.codex.FollowedCodexHeader
import com.theoriacodex.app.codex.SavedSearchesCodexFilters
import com.theoriacodex.app.codex.SavedSearchesCodexHeader
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.Post

internal data class VirtualCodexUi(
    val header: @Composable (List<Post>, CodexCollectionFilters, Boolean, LazyStaggeredGridState) -> Unit,
    val filters: @Composable (() -> Unit) -> Unit,
    val filtersActive: Boolean,
    val emptyMessage: String,
)

/** Keeps virtual collection chrome out of the shell; ordinary collection mutation actions stay separate. */
internal fun virtualCodexUi(
    state: CodexDetailDestinationState,
    restore: FeedFabRestoreState,
    onChange: (FeedFabRestoreState) -> Unit,
    onBack: () -> Unit,
    onManageFollowed: () -> Unit,
    onRenameSearch: (String, String) -> Unit,
    onRemoveSearch: (String) -> Unit,
): VirtualCodexUi? {
    state.followedOwner?.let { owner ->
        return VirtualCodexUi(
            header = { visible, filters, resolving, grid ->
                FollowedCodexHeader(owner, state.follows, visible, state.posts, filters, resolving, grid, onBack, onManageFollowed)
            },
            filters = { dismiss -> FollowedCodexFilters(state.follows, restore, onChange, { dismiss(); onManageFollowed() }) },
            filtersActive = restore.followedSources.isNotEmpty() || restore.followedAuthors.isNotEmpty(),
            emptyMessage = "Follow a creator or adjust your filters",
        )
    }
    state.savedSearchOwner?.let { owner ->
        return VirtualCodexUi(
            header = { visible, filters, resolving, grid ->
                SavedSearchesCodexHeader(owner, state.savedSearches, visible, state.posts, filters, resolving, grid,
                    onBack, restore, onChange, onRenameSearch, onRemoveSearch)
            },
            filters = { SavedSearchesCodexFilters(state.savedSearches, restore, onChange, onRenameSearch, onRemoveSearch) },
            filtersActive = state.savedSearches.any { it.id in restore.excludedSavedSearches },
            emptyMessage = "Save a search from Recents or include a search in this feed",
        )
    }
    return null
}
