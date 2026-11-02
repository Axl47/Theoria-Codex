package com.theoriacodex.app.codex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.domain.model.Post

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedSearchesCodexHeader(
    owner: SavedSearchesCodexViewModel,
    entries: List<SavedSearchEntry>,
    visiblePosts: List<Post>,
    posts: List<Post>,
    filters: CodexCollectionFilters,
    resolvingDurations: Boolean,
    gridState: LazyStaggeredGridState,
    onBack: () -> Unit,
    restoreState: FeedFabRestoreState,
    onChange: (FeedFabRestoreState) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var showSearches by remember { mutableStateOf(false) }
    LiveCodexHeader("Searches", SAVED_SEARCHES_CODEX_ID, owner,
        entries.associate { it.id to it.name }, visiblePosts, posts, filters, resolvingDurations, gridState,
        onBack, "Searches", onManage = { showSearches = true })
    if (showSearches) SavedSearchesManagementSheet(entries, restoreState, onChange, onRename, onRemove) {
        showSearches = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedSearchesManagementSheet(
    entries: List<SavedSearchEntry>,
    restoreState: FeedFabRestoreState,
    onChange: (FeedFabRestoreState) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ExpandedBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SavedSearchesCodexFilters(entries, restoreState, onChange, onRename, onRemove)
        }
    }
}
