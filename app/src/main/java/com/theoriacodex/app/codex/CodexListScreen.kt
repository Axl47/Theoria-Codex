package com.theoriacodex.app.codex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.SourceIcon
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.components.ActionMenuContentRow
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.zIndex
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.components.ReorderAutoScrollEffect
import com.theoriacodex.app.ui.components.reorderRowPlacement

@Composable
internal fun CodexListContent(
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    val codices = presentation.codices

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CodexListHeader(codices, state, actions)

        CodexListBody(presentation, state, actions)
    }
}

@Composable
private fun CodexListHeader(
    codices: List<Codex>,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Codex Library", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CodexActionIcon(Icons.Default.FileOpen, "Import Codex", onClick = actions.importCodex)
            CodexActionIcon(
                icon = if (state.ordering.reorderMode) Icons.Default.Check else Icons.Default.Reorder,
                description = if (state.ordering.reorderMode) "Done reordering Codices" else "Reorder Codices",
                onClick = { state.ordering.toggle(codices, actions.commitReorder) },
            )
            CodexActionIcon(Icons.Default.Add, "Create Codex") { state.showCreateDialog = true }
        }
    }
}

@Composable
private fun CodexListBody(
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    when {
        state.ordering.reorderMode -> CodexReorderList(presentation, state, actions.setLibrarySort)
        else -> CodexGrid(presentation, state, actions.openCodex, actions.openSavedCollectionActions)
    }
}

@Composable
private fun CodexReorderList(
    presentation: CodexListPresentation,
    state: CodexListUiState,
    onSortChange: (CodexLibrarySort) -> Unit,
) {
    // The draft stays in manual order underneath an automatic sort, so Done can always commit it.
    val rows = sortCodicesForLibrary(
        state.ordering.reorderDraft, presentation.librarySort, presentation.itemCounts, presentation.likesCodexId,
        presentation.libraryUsage.orEmpty(),
    )
    val list = rememberLazyListState()
    val rowSpacing = with(LocalDensity.current) { 8.dp.toPx() }
    SideEffect { state.ordering.itemSpacingPx = rowSpacing }
    ReorderAutoScrollEffect(state.ordering, list)
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CodexLibrarySortSelector(presentation.librarySort, onSortChange)
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth().weight(1f),
            userScrollEnabled = state.ordering.draggingId == null,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(rows, key = { _, codex -> codex.codexId }) { index, codex ->
                CodexReorderRow(codex, index, presentation, state, Modifier.reorderRowPlacement(this, state.ordering.draggingId == codex.codexId))
            }
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
            CodexReorderMetadata(
                if (codex.codexId == presentation.likesCodexId) codex.copy(name = "Saved") else codex,
                presentation.itemCounts[codex.codexId] ?: 0, Modifier.weight(1f),
            )
            if (presentation.librarySort == CodexLibrarySort.MANUAL) CodexReorderHandle(codex, index, state)
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
            "$itemCount items", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CodexGrid(
    presentation: CodexListPresentation,
    state: CodexListUiState,
    onOpenCodex: (String) -> Unit,
    onOpenSavedCollectionActions: (String) -> Unit,
) {
    // A first frame in manual order would move the restored scroll anchor to the wrong Codex.
    val awaitingCounts = presentation.librarySort.usesItemCounts &&
        presentation.codices.any { it.codexId !in presentation.itemCounts }
    val awaitingUsage = presentation.librarySort.usesUsage && presentation.libraryUsage == null
    if (awaitingCounts || awaitingUsage) return
    val sorted = sortCodicesForLibrary(
        presentation.codices, presentation.librarySort, presentation.itemCounts, presentation.likesCodexId,
        presentation.libraryUsage.orEmpty(),
    )
    val entries = savedCodexLibraryEntries(sorted, presentation.likesCodexId)
    val gridState = rememberLazyGridState()
    ScrollToTopOnTabReselect(TopLevelDestination.Codex) { gridState.animateScrollToItem(0) }
    com.theoriacodex.app.ui.components.AdaptiveMediaGrid(
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        entries.forEachIndexed { index, codex ->
            val saved = codex.codexId == SAVED_CODEX_ID
            item(key = codex.codexId, span = { GridItemSpan(if (saved) maxLineSpan else 1) }) {
                if (saved) {
                    SavedLibrarySection(
                        presentation, index > 0, index < entries.lastIndex, onOpenCodex, onOpenSavedCollectionActions,
                    )
                } else {
                    CodexLibraryTile(codex, presentation, state, onOpenCodex)
                }
            }
        }
    }
}

/** Manual order can place Saved anywhere, so it is set apart on whichever sides have Codices. */
@Composable
private fun SavedLibrarySection(
    presentation: CodexListPresentation,
    dividerAbove: Boolean,
    dividerBelow: Boolean,
    onOpenCodex: (String) -> Unit,
    onOpenSavedCollectionActions: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (dividerAbove) HorizontalDivider()
        SavedCodexLibraryTile(presentation, onOpenCodex, onOpenSavedCollectionActions)
        if (dividerBelow) HorizontalDivider()
    }
}

@Composable
private fun CodexLibraryTile(
    codex: Codex,
    presentation: CodexListPresentation,
    state: CodexListUiState,
    onOpenCodex: (String) -> Unit,
) {
    CodexCoverTile(
        codexId = codex.codexId,
        name = codex.name,
        itemCount = presentation.itemCounts[codex.codexId] ?: 0,
        covers = presentation.coverCandidates[codex.codexId].orEmpty(),
        onOpen = { onOpenCodex(codex.codexId) },
        onOpenActions = { state.actionTarget = codex },
        modifier = Modifier.fillMaxWidth().height(168.dp),
    )
}

@Composable
internal fun CodexListOverlays(
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    val codices = presentation.codices
    codices.firstOrNull { it.codexId == state.cleaningTargetId }?.let { target ->
        CodexCleaningSheet(
            target = target,
            codices = codices,
            isLikesCodex = target.codexId == presentation.likesCodexId,
            onDismiss = { state.cleaningTargetId = null },
            onClean = { comparisonIds ->
                state.cleaningTargetId = null
                actions.cleanCodex(target.codexId, comparisonIds)
            },
        )
    }
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

@Composable
private fun CodexDeleteConfirmation(
    state: CodexListUiState,
    likesCodexId: String,
    onDeleteCodex: (String) -> Unit,
) {
    val codex = state.deleteTarget ?: return
    val clearsLikes = codex.codexId == likesCodexId
    AlertDialog(
        onDismissRequest = { state.deleteTarget = null },
        title = { Text(if (clearsLikes) "Clear Likes?" else "Delete Codex?") },
        text = {
            Text(
                if (clearsLikes) {
                    "Clear all liked posts from this recommendation profile? The Likes Codex will remain."
                } else {
                    "Delete \"${codex.name}\" and all saved items in it? This cannot be undone."
                },
            )
        },
        dismissButton = { TextButton(onClick = { state.deleteTarget = null }) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = {
                onDeleteCodex(codex.codexId)
                state.deleteTarget = null
            }) { Text(if (clearsLikes) "Clear" else "Delete") }
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
        details = { ActionMenuDetailText("$itemCount ${if (itemCount == 1) "post" else "posts"}") },
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
    ) { state.deleteTarget = codex; close() }
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
