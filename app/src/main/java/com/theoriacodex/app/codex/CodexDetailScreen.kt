package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.PostActionSheet
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexDetailScreen(
    codexName: String?,
    posts: List<Post>,
    sortMode: CodexSortMode,
    creatorBrowsingSources: Set<SourceKey>,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    onSortChange: (CodexSortMode) -> Unit,
    onOpenViewer: (Int) -> Unit,
    resolvePostById: suspend (PostId) -> Post? = { null },
    onRemovePost: (Post) -> Unit,
    onSavePostToDevice: (Post) -> Unit,
    onOpenCreatorProfile: (CreatorProfile) -> Unit,
    onOpenLegacyCreatorProfile: (Post) -> Unit,
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    onAddIncludeTerm: (Post, SearchTerm) -> Boolean = { _, _ -> false },
    onAddExcludeTerm: (Post, SearchTerm) -> Boolean = { _, _ -> false },
    onRemoveIncludeTerm: (Post, SearchTerm) -> Unit = { _, _ -> },
    onRemoveExcludeTerm: (Post, SearchTerm) -> Unit = { _, _ -> },
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)? = null,
    onGoToSearch: (() -> Unit)? = null,
    onBack: () -> Unit,
    onDeleteCodex: () -> Unit,
) {
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (codexName == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("This codex no longer exists.", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onBack) { Text("Back to codex list") }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(codexName, style = MaterialTheme.typography.titleLarge)
                Text("${posts.size} items", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onBack) { Text("Back") }
                TextButton(onClick = { showDeleteConfirm = true }) { Text("Delete") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CodexSortMode.entries.forEach { mode ->
                val label = when (mode) {
                    CodexSortMode.NEWEST_SAVED -> "Newest"
                    CodexSortMode.OLDEST_SAVED -> "Oldest"
                    CodexSortMode.BY_SOURCE -> "By source"
                }
                FilterChip(
                    selected = mode == sortMode,
                    onClick = { onSortChange(mode) },
                    label = { Text(label) },
                )
            }
        }

        if (posts.isEmpty()) {
            FeedEmptyTile(
                title = "This codex is empty",
                message = "Browse and save posts",
                contentPadding = 24.dp,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(posts) { index, post ->
                    SearchResultCard(
                        post = post,
                        pixivUgoiraClient = pixivUgoiraClient,
                        showSourceBadge = true,
                        resolvePostById = resolvePostById,
                        refreshOnPreviewError = true,
                        onClick = { onOpenViewer(index) },
                        onLongPress = { selectedActionPost = post },
                    )
                }
            }
        }
    }

    if (selectedActionPost != null) {
        val post = requireNotNull(selectedActionPost)
        PostActionSheet(
            post = post,
            creatorBrowsingSources = creatorBrowsingSources,
            onDismiss = { selectedActionPost = null },
            onSaveToDevice = { onSavePostToDevice(post) },
            onRemoveFromCodex = { onRemovePost(post) },
            onOpenCreatorProfile = onOpenCreatorProfile,
            onOpenLegacyCreatorProfile = { onOpenLegacyCreatorProfile(post) },
            onGoToSearch = onGoToSearch,
            tagContent = {
                PostTagActionSection(
                    post = post,
                    tagVideoCountProvider = tagVideoCountProvider,
                    fetchTagVideoCounts = fetchTagVideoCounts,
                    onAddIncludeTerm = { term -> onAddIncludeTerm(post, term) },
                    onAddExcludeTerm = { term -> onAddExcludeTerm(post, term) },
                    onRemoveIncludeTerm = { term -> onRemoveIncludeTerm(post, term) },
                    onRemoveExcludeTerm = { term -> onRemoveExcludeTerm(post, term) },
                    onFavoriteTagLongPress = onFavoriteTagLongPress,
                )
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Codex?") },
            text = {
                Text("Delete \"$codexName\" and all saved items in it? This cannot be undone.")
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteCodex()
                    },
                ) {
                    Text("Delete")
                }
            },
        )
    }
}
