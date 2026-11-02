package com.theoriacodex.app.codex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
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
import com.theoriacodex.app.tags.TagSelectionSurface
import com.theoriacodex.app.ui.components.ActionMenuDetailText
import com.theoriacodex.app.ui.components.ActionMenuDivider
import com.theoriacodex.app.ui.components.ActionMenuFollowUpRow
import com.theoriacodex.app.ui.components.ActionMenuHeader
import com.theoriacodex.app.ui.components.ActionMenuRow
import com.theoriacodex.app.ui.components.ActionMenuSheet
import com.theoriacodex.domain.model.Codex
import androidx.compose.material.icons.filled.AutoAwesome
import kotlin.math.roundToInt

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
        state.ordering.reorderMode -> CodexReorderList(presentation, state)
        else -> CodexGrid(presentation, state, actions.openCodex, actions.openSavedCollectionActions)
    }
}

@Composable
private fun CodexReorderList(presentation: CodexListPresentation, state: CodexListUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = state.ordering.draggingId == null,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(state.ordering.reorderDraft, key = { _, codex -> codex.codexId }) { index, codex ->
            CodexReorderRow(codex, index, presentation, state)
        }
    }
}

@Composable
private fun CodexReorderRow(
    codex: Codex,
    index: Int,
    presentation: CodexListPresentation,
    state: CodexListUiState,
) {
    val isDragging = state.ordering.draggingId == codex.codexId
    Surface(
        modifier = Modifier.fillMaxWidth().graphicsLayer {
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
    com.theoriacodex.app.ui.components.AdaptiveMediaGrid(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val likesId = presentation.likesCodexId
        savedCodexLibraryEntries(presentation.codices, likesId).forEach { codex ->
            item(key = codex.codexId, span = { GridItemSpan(if (codex.codexId == SAVED_CODEX_ID) maxLineSpan else 1) }) {
                CodexLibraryTile(codex, presentation, state, onOpenCodex, onOpenSavedCollectionActions)
            }
        }
    }
}

@Composable
private fun CodexLibraryTile(
    codex: Codex,
    presentation: CodexListPresentation,
    state: CodexListUiState,
    onOpenCodex: (String) -> Unit,
    onOpenSavedCollectionActions: (String) -> Unit,
) {
    val saved = codex.codexId == SAVED_CODEX_ID
    if (saved) {
        SavedCodexLibraryTile(presentation, onOpenCodex, onOpenSavedCollectionActions)
        return
    }
    val openActions = { state.actionTarget = codex }
    val covers = presentation.coverCandidates[codex.codexId].orEmpty()
    CodexGridTile(
        codex = codex,
        itemCount = presentation.itemCounts[codex.codexId] ?: 0,
        coverCandidates = covers,
        onOpen = { onOpenCodex(codex.codexId) },
        onOpenActions = openActions,
        onLongPress = openActions,
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

    val searchCodex = state.searchSourceTarget
    if (searchCodex != null) {
        CodexSourceSheet(
            codex = searchCodex,
            options = presentation.searchSourceOptions[searchCodex.codexId].orEmpty(),
            state = state,
        )
    }

    val tagSelection = state.tagSelectionTarget
    if (tagSelection != null) {
        val tagOptions = presentation.searchTagOptions[tagSelection.codex.codexId]
            ?.get(tagSelection.source)
            .orEmpty()
        CodexSearchTagSelectionSheet(
            selection = tagSelection,
            tags = tagOptions,
            onDismiss = { state.tagSelectionTarget = null },
            onApply = { includeTags ->
                state.tagSelectionTarget = null
                actions.searchFromCodex(tagSelection.codex.codexId, tagSelection.source, includeTags)
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
        CodexNameDialog(
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
        CodexNameDialog(
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
        state.searchSourceTarget = codex; close()
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

@Composable
private fun CodexSourceSheet(
    codex: Codex,
    options: List<CodexSearchSourceOption>,
    state: CodexListUiState,
) {
    ActionMenuSheet(onDismiss = { state.searchSourceTarget = null }) {
        Text(
            "Search ${codex.name} tags on", style = MaterialTheme.typography.titleMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp),
        )
        if (options.isEmpty()) ActionMenuDetailText("No searchable sources available")
        options.forEach { option ->
            val label = if (option.postCount == 1) "post" else "posts"
            ActionMenuContentRow(
                label = option.source.displayName(),
                leading = { SourceIcon(option.source) },
                trailingText = "${option.postCount} $label",
            ) {
                state.searchSourceTarget = null
                state.tagSelectionTarget = CodexSourceSelection(codex, option.source)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodexSearchTagSelectionSheet(
    selection: CodexSourceSelection,
    tags: List<CodexSearchTagOption>,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit,
) {
    var selectedTags by remember(selection.codex.codexId, selection.source) {
        mutableStateOf<Set<String>>(emptySet())
    }
    val maxRandomAmount = tags.size.coerceAtMost(MAX_CODEX_RANDOM_TAG_AMOUNT)
    var randomAmount by remember(selection.codex.codexId, selection.source, tags.size) {
        mutableIntStateOf(
            when {
                tags.isEmpty() -> 0
                else -> minOf(DEFAULT_CODEX_RANDOM_TAG_AMOUNT, maxRandomAmount.coerceAtLeast(1))
            }
        )
    }
    LaunchedEffect(maxRandomAmount) {
        randomAmount = when {
            maxRandomAmount <= 0 -> 0
            randomAmount <= 0 -> 1
            else -> randomAmount.coerceAtMost(maxRandomAmount)
        }
    }

    CodexTagSelectionContent(
        selection, tags, selectedTags, randomAmount, maxRandomAmount, onDismiss, onApply,
        onSelectedTagsChanged = { selectedTags = it },
        onRandomAmountChanged = { randomAmount = it },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodexTagSelectionContent(
    selection: CodexSourceSelection,
    tags: List<CodexSearchTagOption>,
    selectedTags: Set<String>,
    randomAmount: Int,
    maxRandomAmount: Int,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit,
    onSelectedTagsChanged: (Set<String>) -> Unit,
    onRandomAmountChanged: (Int) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CodexTagSelectionHeader(selection)

            CodexRandomTagSelection(tags, randomAmount, maxRandomAmount, onSelectedTagsChanged, onRandomAmountChanged)

            CodexSearchApplyButton(
                enabled = selectedTags.isNotEmpty(),
                onClick = {
                    val includeTags = tags
                        .map { option -> option.tag }
                        .filter { tag -> tag in selectedTags }
                    onApply(includeTags)
                },
            )

            CodexSearchTagSelectionGrid(
                tags = tags,
                selectedTags = selectedTags,
                onToggleTag = { tag ->
                    onSelectedTagsChanged(if (tag in selectedTags) selectedTags - tag else selectedTags + tag)
                },
            )

            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun CodexTagSelectionHeader(selection: CodexSourceSelection) {
    Text(
        text = selection.codex.name,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Text(
        text = selection.source.displayName(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

}

@Composable
private fun CodexRandomTagSelection(
    tags: List<CodexSearchTagOption>,
    randomAmount: Int,
    maxRandomAmount: Int,
    onSelectedTagsChanged: (Set<String>) -> Unit,
    onRandomAmountChanged: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Tag Amount: ${randomAmount.coerceAtLeast(0)}",
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(
                enabled = tags.isNotEmpty(),
                onClick = {
                    onSelectedTagsChanged(tags.map { it.tag }.shuffled()
                        .take(randomAmount.coerceIn(0, tags.size)).toSet())
                },
            ) {
                Text("Randomize")
            }
        }
        Slider(
            value = randomAmount.coerceAtLeast(1).toFloat(),
            onValueChange = { value ->
                onRandomAmountChanged(value.roundToInt().coerceIn(1, maxRandomAmount.coerceAtLeast(1)))
            },
            enabled = maxRandomAmount > 1,
            valueRange = 1f..maxRandomAmount.coerceAtLeast(2).toFloat(),
            steps = if (maxRandomAmount > 1) maxRandomAmount - 2 else 0,
        )
    }

}

@Composable
private fun CodexSearchApplyButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val surfaceColor = if (enabled) {
        accent.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val textColor = if (enabled) {
        accent
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        color = surfaceColor,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = "Apply",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun CodexSearchTagSelectionGrid(
    tags: List<CodexSearchTagOption>,
    selectedTags: Set<String>,
    onToggleTag: (String) -> Unit,
) {
    if (tags.isEmpty()) {
        Text(
            text = "No searchable tags",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        )
        return
    }

    val rows = remember(tags) { tags.chunked(3) }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(rows) { _, rowTags ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                rowTags.forEach { option ->
                    CodexSearchTagSelectionCell(
                        option = option,
                        selected = option.tag in selectedTags,
                        onToggle = { onToggleTag(option.tag) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - rowTags.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CodexSearchTagSelectionCell(
    option: CodexSearchTagOption,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TagSelectionSurface(
            tag = option.tag,
            active = selected,
            modifier = Modifier.fillMaxWidth(),
            longPressModifier = Modifier.clickable(onClick = onToggle),
        )
        Text(
            text = option.count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
        )
    }
}

private const val DEFAULT_CODEX_RANDOM_TAG_AMOUNT = 3
private const val MAX_CODEX_RANDOM_TAG_AMOUNT = 10

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodexGridTile(
    codex: Codex,
    itemCount: Int,
    coverCandidates: List<CodexCoverCandidate>,
    onOpen: () -> Unit,
    onOpenActions: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongPress,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(164.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            CodexCoverImage(
                candidates = coverCandidates,
                contentDescription = codex.name,
                modifier = Modifier.fillMaxSize(),
                fallback = {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                },
            )
            com.theoriacodex.app.ui.components.MediaCountBadge(
                itemCount.toString(), "Posts in ${codex.name}", Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
        }

        CodexTileMetadata(codex = codex, onOpenActions = onOpenActions)
    }
}
