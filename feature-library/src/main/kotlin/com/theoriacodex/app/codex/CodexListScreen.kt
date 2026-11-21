package com.theoriacodex.app.codex

import com.theoriacodex.app.library.LibrarySectionHeader
import com.theoriacodex.app.ui.undo.UndoLeaveEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.ActionMenuDetailText
import com.theoriacodex.app.ui.components.ActionMenuDivider
import com.theoriacodex.app.ui.components.ActionMenuFollowUpRow
import com.theoriacodex.app.ui.components.ActionMenuHeader
import com.theoriacodex.app.ui.components.ActionMenuRow
import com.theoriacodex.app.ui.components.ActionMenuSheet
import com.theoriacodex.app.ui.components.NameEntryDialog
import com.theoriacodex.data.repository.CodexLibrarySort
import com.theoriacodex.domain.model.Codex
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.zIndex
import com.theoriacodex.app.ui.components.ReorderAutoScrollEffect
import com.theoriacodex.app.ui.components.reorderRowPlacement

@Composable
internal fun CodexListContent(
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
    header: LibrarySectionHeader,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header { CodexListHeaderActions(presentation, state, actions) }
        // A deleted Codex's Undo lives until the Library is left; leaving commits the deletion's loss.
        UndoLeaveEffect(CodexUndoAnchors.LIBRARY_SCOPE)

        CodexListBody(presentation, state, actions)
    }
}

/**
 * Import, Sort and Create, placed in the Library header while Codices shows. Choosing Manual in the sort menu
 * opens the reorder list, whose Done replaces these until it commits.
 */
@Composable
private fun CodexListHeaderActions(
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    val reorder = { state.ordering.toggle(presentation.libraryCodices, actions.commitReorder) }
    if (state.ordering.reorderMode) return CodexActionIcon(Icons.Default.Check, "Done reordering Codices", onClick = reorder)
    CodexActionIcon(Icons.Default.FileOpen, "Import Codex", onClick = actions.importCodex)
    CodexLibrarySortMenu(presentation.librarySort) { sort ->
        actions.setLibrarySort(sort)
        if (sort == CodexLibrarySort.MANUAL) reorder()
    }
    CodexActionIcon(Icons.Default.Add, "Create Codex") { state.showCreateDialog = true }
}

@Composable
private fun CodexListBody(
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    when {
        state.ordering.reorderMode -> CodexReorderList(presentation, state)
        else -> CodexGrid(presentation, state, actions)
    }
}

@Composable
private fun CodexReorderList(
    presentation: CodexListPresentation,
    state: CodexListUiState,
) {
    // Reordering is only opened from Manual, so the rows are the draft itself.
    val rows = state.ordering.reorderDraft
    val list = rememberLazyListState()
    val rowSpacing = with(LocalDensity.current) { 8.dp.toPx() }
    SideEffect { state.ordering.itemSpacingPx = rowSpacing }
    ReorderAutoScrollEffect(state.ordering, list)
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = state.ordering.draggingId == null,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(rows, key = { _, codex -> codex.codexId }) { index, codex ->
            CodexReorderRow(codex, index, presentation, state, Modifier.reorderRowPlacement(this, state.ordering.draggingId == codex.codexId))
        }
    }
}

@Composable
private fun CodexReorderRow(
    codex: Codex,
    index: Int,
    presentation: CodexListPresentation,
    state: CodexListUiState,
    modifier: Modifier,
) {
    val isDragging = state.ordering.draggingId == codex.codexId
    Surface(
        modifier = modifier.zIndex(if (isDragging) 1f else 0f).fillMaxWidth().graphicsLayer {
            if (isDragging) {
                translationY = state.ordering.dragOffsetY
                shadowElevation = 14f
            }
        }.onSizeChanged { size ->
            size.height.toFloat().takeIf { it > 1f }?.let { state.ordering.itemHeightPx = it }
        }.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = if (isDragging) 6.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CodexReorderCover(codex, presentation.coverCandidates[codex.codexId].orEmpty())
            CodexReorderMetadata(codex, presentation.itemCounts[codex.codexId] ?: 0, Modifier.weight(1f))
            CodexReorderHandle(codex, index, state)
        }
    }
}

@Composable
private fun CodexReorderCover(codex: Codex, candidates: List<CodexCoverCandidate>) {
    Box(
        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        CodexCoverImage(candidates, codex.name, Modifier.fillMaxSize()) {
            Icon(Icons.Default.Image, contentDescription = null)
        }
    }
}

@Composable
private fun CodexReorderMetadata(codex: Codex, itemCount: Int, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            codex.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            codexPostCount(itemCount), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun CodexListOverlays(
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    val codices = presentation.codices
    CodexCleaningOverlay(presentation, state, actions)
    CodexNameOverlays(state, actions)
    CodexDeleteConfirmation(state, presentation.likesCodexId, actions.deleteCodex)

    val actionCodex = state.actionTarget?.let { target ->
        codices.firstOrNull { codex -> codex.codexId == target.codexId } ?: target
    }
    if (actionCodex != null) {
        CodexActionSheet(actionCodex, presentation, state, actions)
    }

    CodexAutoSaveOverlay(presentation, state, actions)

    state.searchTarget?.let { codex ->
        CodexTagSearchSheet(
            codexName = codex.name,
            sources = presentation.searchSourceOptions[codex.codexId].orEmpty(),
            terms = presentation.searchTerms,
            onDismiss = { state.searchTarget = null },
            onSearch = { source, query ->
                state.searchTarget = null
                actions.searchFromCodex(codex.codexId, source, query)
            },
        )
    }
}

@Composable
private fun CodexCleaningOverlay(presentation: CodexListPresentation, state: CodexListUiState, actions: CodexListActions) {
    val codices = presentation.codices
    val target = codices.firstOrNull { it.codexId == state.cleaningTargetId } ?: return
    val comparisonIds = remember(target, codices) { codexCleaningComparisons(target, codices).map(Codex::codexId) }
    val matches by remember(target.codexId, comparisonIds, presentation.observeCleaningMatches) {
        presentation.observeCleaningMatches(target.codexId, comparisonIds)
    }.collectAsStateWithLifecycle(emptyMap())
    CodexCleaningSheet(
        target = target,
        codices = codices,
        isLikesCodex = target.codexId == presentation.likesCodexId,
        matches = matches,
        onDismiss = { state.cleaningTargetId = null },
        onClean = { comparisonIds ->
            state.cleaningTargetId = null
            actions.cleanCodex(target.codexId, comparisonIds)
        },
    )
}

@Composable
private fun CodexAutoSaveOverlay(
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    val target = state.autoSaveTarget ?: return
    // Rule edits arrive through the live list, so the sheet must not render the tapped snapshot.
    val codex = presentation.codices.firstOrNull { it.codexId == target.codexId } ?: target
    CodexAutoSaveSheet(
        codexName = codex.name,
        automaticTags = codex.automaticTags,
        tagOptionsBySource = presentation.searchTagOptions[codex.codexId].orEmpty(),
        loading = presentation.actionLoading,
        failed = presentation.actionFailed,
        onRetry = presentation.retryActionOptions,
        onSetAutomaticTag = { tag, enabled -> actions.setAutomaticTag(codex.codexId, tag, enabled) },
        onDismiss = { state.autoSaveTarget = null },
    )
}

@Composable
private fun CodexNameOverlays(state: CodexListUiState, actions: CodexListActions) {
    if (state.showCreateDialog) {
        NameEntryDialog(
            title = "Create Codex",
            initialName = "",
            onDismiss = { state.showCreateDialog = false },
            onSave = { name ->
                actions.createCodex(name)
                state.showCreateDialog = false
            },
        )
    }
    state.renameTarget?.let { codex ->
        NameEntryDialog(
            title = "Rename Codex",
            initialName = codex.name,
            onDismiss = { state.renameTarget = null },
            onSave = { name ->
                actions.renameCodex(codex.codexId, name)
                state.renameTarget = null
            },
        )
    }
}

/** Only clearing Likes is confirmed: it also unlikes every post. Deleting a Codex acts at once and offers Undo. */
@Composable
private fun CodexDeleteConfirmation(
    state: CodexListUiState,
    likesCodexId: String,
    onDeleteCodex: (String) -> Unit,
) {
    val codex = state.deleteTarget ?: return
    if (codex.codexId != likesCodexId) return
    AlertDialog(
        onDismissRequest = { state.deleteTarget = null },
        title = { Text("Clear likes?") },
        text = { Text("Every post liked in this profile is unliked.") },
        dismissButton = { TextButton(onClick = { state.deleteTarget = null }) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = {
                onDeleteCodex(codex.codexId)
                state.deleteTarget = null
            }) { Text("Clear") }
        },
    )
}

/** Long-press and overflow menu; rule editing lives in [CodexAutoSaveSheet]. */
@Composable
private fun CodexActionSheet(
    codex: Codex,
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    val isLikesCodex = codex.codexId == presentation.likesCodexId
    ActionMenuSheet(onDismiss = { state.actionTarget = null }) {
        CodexActionHeader(codex, presentation)
        // Likes already receives every liked post, so it has no rules to configure.
        if (!isLikesCodex) {
            ActionMenuFollowUpRow(Icons.Default.AutoAwesome, "Auto-save") {
                state.autoSaveTarget = codex
                state.actionTarget = null
            }
        }
        CodexActionRows(
            codex = codex,
            isLikesCodex = isLikesCodex,
            searchEnabled = presentation.searchSourceOptions[codex.codexId].orEmpty().isNotEmpty(),
            state = state,
            actions = actions,
        )
    }
}

@Composable
private fun CodexActionHeader(codex: Codex, presentation: CodexListPresentation) {
    val itemCount = presentation.itemCounts[codex.codexId] ?: 0
    ActionMenuHeader(
        title = codex.name,
        image = { modifier ->
            CodexCoverImage(presentation.coverCandidates[codex.codexId].orEmpty(), codex.name, modifier) {
                Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        details = { ActionMenuDetailText(codexPostCount(itemCount)) },
    )
}

@Composable
private fun CodexActionRows(
    codex: Codex,
    isLikesCodex: Boolean,
    searchEnabled: Boolean,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    val close = { state.actionTarget = null }
    ActionMenuRow(Icons.Default.Search, "Search with these tags", searchEnabled) {
        state.searchTarget = codex; close()
    }
    ActionMenuRow(Icons.Default.Share, "Share") { close(); actions.shareCodex(codex.codexId) }
    ActionMenuDivider()
    ActionMenuRow(Icons.Default.Download, "Download") { close(); actions.downloadCodex(codex.codexId) }
    actions.makeAvailableOffline?.let { makeOffline ->
        ActionMenuRow(Icons.Default.OfflinePin, "Make available offline") {
            close(); makeOffline(codex.codexId)
        }
    }
    ActionMenuDivider()
    ActionMenuRow(Icons.Default.Edit, "Rename") { state.renameTarget = codex; close() }
    ActionMenuRow(Icons.Default.CleaningServices, "Clean") { close(); state.cleaningTargetId = codex.codexId }
    ActionMenuRow(
        icon = Icons.Default.Delete,
        label = if (isLikesCodex) "Clear likes" else "Delete Codex",
        tint = MaterialTheme.colorScheme.error,
    ) {
        close()
        if (isLikesCodex) state.deleteTarget = codex else actions.deleteCodex(codex.codexId)
    }
}

@Composable
private fun CodexActionIcon(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(enabled = enabled, onClick = onClick) {
        Icon(icon, contentDescription = description)
    }
}
