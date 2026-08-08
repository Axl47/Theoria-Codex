package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.PostActionSheet
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
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
    onRemovePosts: (List<Post>) -> Unit,
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
    var editSelection by remember { mutableStateOf(CodexEditSelection()) }

    LaunchedEffect(posts.map(Post::id)) {
        editSelection = editSelection.retainAvailable(posts.mapTo(mutableSetOf(), Post::id))
    }

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
        CodexDetailHeader(
            codexName = codexName,
            itemCount = posts.size,
            sortMode = sortMode,
            editSelection = editSelection,
            onSortChange = onSortChange,
            onBack = onBack,
            onBeginEdit = { editSelection = editSelection.begin() },
            onCancelEdit = { editSelection = editSelection.exit() },
            onRemoveSelected = {
                val selected = posts.filter { it.id in editSelection.selectedPostIds }
                editSelection = editSelection.exit()
                onRemovePosts(selected)
            },
            onDeleteCodex = { showDeleteConfirm = true },
        )
        CodexDetailGrid(
            posts = posts,
            editSelection = editSelection,
            pixivUgoiraClient = pixivUgoiraClient,
            resolvePostById = resolvePostById,
            onOpenViewer = onOpenViewer,
            onToggleSelection = { postId -> editSelection = editSelection.toggle(postId) },
            onOpenPostActions = { post -> selectedActionPost = post },
        )
    }

    if (selectedActionPost != null) {
        val post = requireNotNull(selectedActionPost)
        PostActionSheet(
            post = post,
            creatorBrowsingSources = creatorBrowsingSources,
            onDismiss = { selectedActionPost = null },
            onSaveToDevice = { onSavePostToDevice(post) },
            onRemoveFromCodex = { onRemovePosts(listOf(post)) },
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

@Composable
private fun CodexDetailHeader(
    codexName: String,
    itemCount: Int,
    sortMode: CodexSortMode,
    editSelection: CodexEditSelection,
    onSortChange: (CodexSortMode) -> Unit,
    onBack: () -> Unit,
    onBeginEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onRemoveSelected: () -> Unit,
    onDeleteCodex: () -> Unit,
) {
    SecondaryScreenAppBar(
        title = codexName,
        subtitle = "$itemCount items",
        onBack = onBack,
    ) {
        CodexDetailHeaderActions(
            hasPosts = itemCount > 0,
            editSelection = editSelection,
            onBeginEdit = onBeginEdit,
            onCancelEdit = onCancelEdit,
            onRemoveSelected = onRemoveSelected,
            onDeleteCodex = onDeleteCodex,
        )
    }
    if (!editSelection.active) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CodexSortMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == sortMode,
                    onClick = { onSortChange(mode) },
                    label = { Text(mode.displayLabel()) },
                )
            }
        }
    }
}

@Composable
private fun CodexDetailHeaderActions(
    hasPosts: Boolean,
    editSelection: CodexEditSelection,
    onBeginEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onRemoveSelected: () -> Unit,
    onDeleteCodex: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (editSelection.active) {
            TextButton(onClick = onCancelEdit) { Text("Cancel") }
            TextButton(
                enabled = editSelection.selectedPostIds.isNotEmpty(),
                onClick = onRemoveSelected,
            ) {
                Text("Remove (${editSelection.selectedPostIds.size})")
            }
        } else {
            TextButton(enabled = hasPosts, onClick = onBeginEdit) { Text("Edit") }
            TextButton(onClick = onDeleteCodex) { Text("Delete") }
        }
    }
}

@Composable
private fun CodexDetailGrid(
    posts: List<Post>,
    editSelection: CodexEditSelection,
    pixivUgoiraClient: PixivUgoiraClient?,
    resolvePostById: suspend (PostId) -> Post?,
    onOpenViewer: (Int) -> Unit,
    onToggleSelection: (PostId) -> Unit,
    onOpenPostActions: (Post) -> Unit,
) {
    if (posts.isEmpty()) {
        FeedEmptyTile(
            title = "This codex is empty",
            message = "Browse and save posts",
            contentPadding = 24.dp,
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(
            items = posts,
            key = { _, post -> "${post.id.source.name}:${post.id.sourcePostId}" },
        ) { index, post ->
            CodexSelectablePostCard(
                post = post,
                index = index,
                editSelection = editSelection,
                pixivUgoiraClient = pixivUgoiraClient,
                resolvePostById = resolvePostById,
                onOpenViewer = onOpenViewer,
                onToggleSelection = onToggleSelection,
                onOpenPostActions = onOpenPostActions,
            )
        }
    }
}

@Composable
private fun CodexSelectablePostCard(
    post: Post,
    index: Int,
    editSelection: CodexEditSelection,
    pixivUgoiraClient: PixivUgoiraClient?,
    resolvePostById: suspend (PostId) -> Post?,
    onOpenViewer: (Int) -> Unit,
    onToggleSelection: (PostId) -> Unit,
    onOpenPostActions: (Post) -> Unit,
) {
    Box {
        SearchResultCard(
            post = post,
            pixivUgoiraClient = pixivUgoiraClient,
            showSourceBadge = true,
            resolvePostById = resolvePostById,
            refreshOnPreviewError = true,
            onClick = {
                if (editSelection.active) onToggleSelection(post.id) else onOpenViewer(index)
            },
            onLongPress = {
                if (editSelection.active) onToggleSelection(post.id) else onOpenPostActions(post)
            },
        )
        if (editSelection.active) {
            CodexSelectionMarker(selected = post.id in editSelection.selectedPostIds)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CodexSelectionMarker(selected: Boolean) {
    Surface(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(8.dp)
            .size(32.dp),
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun CodexSortMode.displayLabel(): String = when (this) {
    CodexSortMode.NEWEST_SAVED -> "Newest"
    CodexSortMode.OLDEST_SAVED -> "Oldest"
    CodexSortMode.BY_SOURCE -> "By source"
}
