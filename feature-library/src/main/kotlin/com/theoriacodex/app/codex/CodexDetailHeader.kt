package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
import com.theoriacodex.domain.model.Post

/** The standard header, for collections whose membership the user can edit. */
@Composable
internal fun CodexEditableHeader(
    codexName: String,
    posts: List<Post>,
    visiblePosts: List<Post>,
    filtersActive: Boolean,
    editSelection: CodexEditSelection,
    isLikesCodex: Boolean,
    onEditSelectionChange: (CodexEditSelection) -> Unit,
    onTitleLongPress: (() -> Unit)?,
    onBack: () -> Unit,
    onAddPostsToAnotherCodex: (List<Post>) -> Unit,
    onRemovePosts: (List<Post>) -> Unit,
    onDeleteCodex: () -> Unit,
) {
    CodexDetailHeader(
        codexName = codexName,
        onTitleLongPress = onTitleLongPress,
        itemSummary = codexItemSummary(
            visibleCount = visiblePosts.size,
            totalCount = posts.size,
            filtersActive = filtersActive,
            selectedCount = editSelection.selectedPostIds.size.takeIf { editSelection.active },
        ),
        itemCount = posts.size,
        editSelection = editSelection,
        onBack = onBack,
        onBeginEdit = { onEditSelectionChange(editSelection.begin()) },
        onCancelEdit = { onEditSelectionChange(editSelection.exit()) },
        onAddSelected = {
            val selected = posts.filter { it.id in editSelection.selectedPostIds }
            onEditSelectionChange(editSelection.exit())
            onAddPostsToAnotherCodex(selected)
        },
        onRemoveSelected = {
            val selected = posts.filter { it.id in editSelection.selectedPostIds }
            onEditSelectionChange(editSelection.exit())
            onRemovePosts(selected)
        },
        onDeleteCodex = onDeleteCodex,
        isLikesCodex = isLikesCodex,
    )
}

@Composable
internal fun CodexDeleteConfirmationDialog(
    visible: Boolean,
    codexName: String,
    isLikesCodex: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isLikesCodex) "Clear Likes?" else "Delete Codex?") },
        text = {
            Text(
                if (isLikesCodex) {
                    "Clear all liked posts from this recommendation profile? The Likes Codex will remain."
                } else {
                    "Delete \"$codexName\" and all saved items in it?"
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (isLikesCodex) "Clear" else "Delete")
            }
        },
    )
}

@Composable
private fun CodexDetailHeader(
    codexName: String,
    onTitleLongPress: (() -> Unit)?,
    itemSummary: String,
    itemCount: Int,
    editSelection: CodexEditSelection,
    onBack: () -> Unit,
    onBeginEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onAddSelected: () -> Unit,
    onRemoveSelected: () -> Unit,
    onDeleteCodex: () -> Unit,
    isLikesCodex: Boolean,
) {
    SecondaryScreenAppBar(
        title = codexName,
        onTitleLongPress = onTitleLongPress,
        subtitle = itemSummary,
        onBack = onBack,
    ) {
        CodexDetailHeaderActions(
            hasPosts = itemCount > 0,
            editSelection = editSelection,
            onBeginEdit = onBeginEdit,
            onCancelEdit = onCancelEdit,
            onAddSelected = onAddSelected,
            onRemoveSelected = onRemoveSelected,
            onDeleteCodex = onDeleteCodex,
            isLikesCodex = isLikesCodex,
        )
    }
}

@Composable
private fun CodexDetailHeaderActions(
    hasPosts: Boolean,
    editSelection: CodexEditSelection,
    onBeginEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onAddSelected: () -> Unit,
    onRemoveSelected: () -> Unit,
    onDeleteCodex: () -> Unit,
    isLikesCodex: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (editSelection.active) {
            TextButton(onClick = onCancelEdit) { Text("Cancel") }
            IconButton(
                enabled = editSelection.selectedPostIds.isNotEmpty(),
                onClick = onAddSelected,
            ) {
                val selectedCount = editSelection.selectedPostIds.size
                Icon(
                    imageVector = Icons.Default.BookmarkAdd,
                    contentDescription = codexSelectedPostActionDescription(
                        action = "Add",
                        selectedCount = selectedCount,
                        destination = "to another Codex",
                    ),
                )
            }
            IconButton(
                enabled = editSelection.selectedPostIds.isNotEmpty(),
                onClick = onRemoveSelected,
            ) {
                val selectedCount = editSelection.selectedPostIds.size
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = codexSelectedPostActionDescription(
                        action = "Remove",
                        selectedCount = selectedCount,
                        destination = "from this Codex",
                    ),
                )
            }
        } else {
            IconButton(enabled = hasPosts, onClick = onBeginEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit Codex")
            }
            IconButton(onClick = onDeleteCodex) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = if (isLikesCodex) "Clear Likes" else "Delete Codex",
                )
            }
        }
    }
}

internal fun codexItemSummary(
    visibleCount: Int,
    totalCount: Int,
    filtersActive: Boolean,
    selectedCount: Int? = null,
): String {
    if (selectedCount != null) return "$selectedCount/$totalCount"
    return if (filtersActive) "$visibleCount of $totalCount items" else "$totalCount items"
}

internal fun codexSelectedPostActionDescription(
    action: String,
    selectedCount: Int,
    destination: String,
): String {
    val postLabel = if (selectedCount == 1) "post" else "posts"
    return "$action $selectedCount selected $postLabel $destination"
}
