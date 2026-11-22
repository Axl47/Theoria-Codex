package com.theoriacodex.app.codex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.InlineNameField
import com.theoriacodex.domain.model.Post

/** What Codex detail's own header can do beyond selection; null entries hide their control. */
class CodexHeaderActions(
    /** Commits a new name typed into the title. */
    val onRename: ((String) -> Unit)?,
    /** Opens the Codex's action list, the same one its Library tile opens. */
    val onOpenActions: (() -> Unit)?,
    /** Re-runs making this Codex available offline, for the offline ring. */
    val onTopUpOffline: (() -> Unit)?,
)

/** The standard header, for collections whose membership the user can edit. */
@Composable
internal fun CodexEditableHeader(
    codexId: String,
    codexName: String,
    posts: List<Post>,
    visiblePosts: List<Post>,
    filtersActive: Boolean,
    editSelection: CodexEditSelection,
    headerActions: CodexHeaderActions,
    onEditSelectionChange: (CodexEditSelection) -> Unit,
    onBack: () -> Unit,
    onAddPostsToAnotherCodex: (List<Post>) -> Unit,
    onRemovePosts: (List<Post>) -> Unit,
) {
    val selected = { posts.filter { it.id in editSelection.selectedPostIds } }
    CodexTitleBar(
        codexName = codexName,
        summary = codexItemSummary(
            visibleCount = visiblePosts.size,
            totalCount = posts.size,
            filtersActive = filtersActive,
            selectedCount = editSelection.selectedPostIds.size.takeIf { editSelection.active },
        ),
        onRename = headerActions.onRename.takeUnless { editSelection.active },
        onBack = onBack,
    ) {
        if (editSelection.active) {
            CodexSelectionActions(
                editSelection,
                onCancel = { onEditSelectionChange(editSelection.exit()) },
                onAdd = { selected().also { onEditSelectionChange(editSelection.exit()) }.let(onAddPostsToAnotherCodex) },
                onRemove = { selected().also { onEditSelectionChange(editSelection.exit()) }.let(onRemovePosts) },
            )
        } else {
            CodexOfflineRingFor(codexId, headerActions.onTopUpOffline)
            IconButton(enabled = posts.isNotEmpty(), onClick = { onEditSelectionChange(editSelection.begin()) }) {
                Icon(Icons.Default.Checklist, contentDescription = "Select posts")
            }
            headerActions.onOpenActions?.let { open ->
                IconButton(onClick = open) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
            }
        }
    }
}

/**
 * Back, the Codex's name and post count, and [actions]. With [onRename], tapping the name turns it into a text
 * field in place: keyboard Done or leaving the field commits a changed name, Back or an empty name leaves it as it was.
 */
@Composable
private fun CodexTitleBar(
    codexName: String,
    summary: String,
    onRename: ((String) -> Unit)?,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        if (renaming && onRename != null) {
            InlineNameField(
                codexName, MaterialTheme.typography.titleMedium,
                Modifier.weight(1f).padding(end = 8.dp).testTag("codex-name-field"),
            ) { name ->
                renaming = false
                name?.let(onRename)
            }
        } else {
            CodexTitle(codexName, summary, Modifier.weight(1f), onRename?.let { { renaming = true } })
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                content = actions,
            )
        }
    }
}

@Composable
private fun CodexTitle(codexName: String, summary: String, modifier: Modifier, onRename: (() -> Unit)?) {
    Column(
        modifier = modifier.then(
            if (onRename != null) Modifier.clickable(onClickLabel = "Rename", onClick = onRename) else Modifier,
        ),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(codexName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CodexSelectionActions(
    editSelection: CodexEditSelection,
    onCancel: () -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    val selectedCount = editSelection.selectedPostIds.size
    TextButton(onClick = onCancel) { Text("Cancel") }
    IconButton(enabled = selectedCount > 0, onClick = onAdd) {
        Icon(
            Icons.Default.BookmarkAdd,
            contentDescription = codexSelectedPostActionDescription("Add", selectedCount, "to another Codex"),
        )
    }
    IconButton(enabled = selectedCount > 0, onClick = onRemove) {
        Icon(
            Icons.Default.Delete,
            contentDescription = codexSelectedPostActionDescription("Remove", selectedCount, "from this Codex"),
        )
    }
}

internal fun codexItemSummary(
    visibleCount: Int,
    totalCount: Int,
    filtersActive: Boolean,
    selectedCount: Int? = null,
): String {
    if (selectedCount != null) return "$selectedCount/$totalCount"
    return if (filtersActive) "$visibleCount of $totalCount posts" else codexPostCount(totalCount)
}

/** "1 post", "3 posts": the one word the Codex screens use for what they hold. */
fun codexPostCount(count: Int): String = "$count ${countNoun(count, "post")}"

internal fun codexSelectedPostActionDescription(
    action: String,
    selectedCount: Int,
    destination: String,
): String {
    val postLabel = if (selectedCount == 1) "post" else "posts"
    return "$action $selectedCount selected $postLabel $destination"
}
