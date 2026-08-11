package com.theoriacodex.app.codex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.tags.TagSelectionSurface
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.SourceKey
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodexListScreen(
    codices: List<Codex>,
    itemCounts: Map<String, Int>,
    codexCoverCandidates: Map<String, List<CodexCoverCandidate>>,
    codexSearchSourceOptions: Map<String, List<CodexSearchSourceOption>>,
    codexSearchTagOptions: Map<String, Map<SourceKey, List<CodexSearchTagOption>>>,
    onOpenCodex: (String) -> Unit,
    onImportCodex: () -> Unit,
    onDownloadCodex: (String) -> Unit,
    onShareCodex: (String) -> Unit,
    onSearchFromCodex: (String, SourceKey, List<String>) -> Unit,
    onCommitReorder: (List<String>) -> Unit,
    onCreateCodex: (String) -> Unit,
    onRenameCodex: (String, String) -> Unit,
    onSetAutomaticTag: (String, CodexAutomaticTag, Boolean) -> Unit,
    onDeleteCodex: (String) -> Unit,
    likesCodexId: String,
) {
    val state = remember { CodexListUiState(codices) }
    val presentation = CodexListPresentation(
        codices, itemCounts, codexCoverCandidates, codexSearchSourceOptions,
        codexSearchTagOptions, likesCodexId,
    )
    val actions = CodexListActions(
        onOpenCodex, onImportCodex, onDownloadCodex, onShareCodex, onSearchFromCodex,
        onCommitReorder, onCreateCodex, onRenameCodex, onSetAutomaticTag, onDeleteCodex,
    )
    LaunchedEffect(codices, state.reorderMode) { state.synchronizeCodices(codices) }
    CodexListContent(presentation, state, actions)
    CodexListOverlays(presentation, state, actions)
}

@Composable
private fun CodexListContent(
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
        Text("Codex", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = actions.importCodex) { Text("Import") }
            TextButton(onClick = { state.toggleReorder(codices, actions.commitReorder) }) {
                Text(if (state.reorderMode) "Done" else "Reorder")
            }
            TextButton(onClick = { state.showCreateDialog = true }) { Text("+ Create") }
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
        presentation.codices.isEmpty() -> EmptyCodexList { state.showCreateDialog = true }
        state.reorderMode -> CodexReorderList(presentation, state)
        else -> CodexGrid(presentation, state, actions.openCodex)
    }
}

@Composable
private fun EmptyCodexList(onCreate: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No codices yet", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onCreate) { Text("Create codex") }
        }
    }
}

@Composable
private fun CodexReorderList(presentation: CodexListPresentation, state: CodexListUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = state.draggingCodexId == null,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(state.reorderDraft, key = { _, codex -> codex.codexId }) { index, codex ->
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
    val isDragging = state.draggingCodexId == codex.codexId
    Surface(
        modifier = Modifier.fillMaxWidth().graphicsLayer {
            if (isDragging) {
                translationY = state.dragOffsetY
                shadowElevation = 14f
            }
        }.onSizeChanged { size ->
            size.height.toFloat().takeIf { it > 1f }?.let { state.itemHeightPx = it }
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
            CodexReorderHandle(codex.codexId, index, state)
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
private fun CodexReorderHandle(codexId: String, index: Int, state: CodexListUiState) {
    IconButton(
        modifier = Modifier.pointerInput(codexId, state.itemHeightPx) {
            detectDragGestures(
                onDragStart = { state.startDrag(codexId, index) },
                onDragCancel = state::resetDrag,
                onDragEnd = state::resetDrag,
                onDrag = { change, dragAmount ->
                    if (state.draggingCodexId != codexId) return@detectDragGestures
                    change.consume()
                    state.drag(codexId, index, dragAmount.y)
                },
            )
        },
        onClick = {},
    ) {
        Icon(Icons.Default.DragHandle, contentDescription = "Drag to reorder")
    }
}

@Composable
private fun CodexGrid(
    presentation: CodexListPresentation,
    state: CodexListUiState,
    onOpenCodex: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(presentation.codices, key = Codex::codexId) { codex ->
            CodexGridTile(
                codex = codex,
                itemCount = presentation.itemCounts[codex.codexId] ?: 0,
                coverCandidates = presentation.coverCandidates[codex.codexId].orEmpty(),
                onOpen = { onOpenCodex(codex.codexId) },
                onOpenActions = { state.actionTarget = codex },
                onLongPress = { state.actionTarget = codex },
            )
        }
    }
}

@Composable
private fun CodexListOverlays(
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    val codices = presentation.codices
    CodexNameOverlays(state, actions)
    CodexDeleteConfirmation(state, presentation.likesCodexId, actions.deleteCodex)

    val actionCodex = state.actionTarget?.let { target ->
        codices.firstOrNull { codex -> codex.codexId == target.codexId } ?: target
    }
    if (actionCodex != null) {
        CodexActionSheet(actionCodex, presentation, state, actions)
    }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodexActionSheet(
    codex: Codex,
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    val isLikesCodex = codex.codexId == presentation.likesCodexId
    ModalBottomSheet(onDismissRequest = { state.actionTarget = null }, dragHandle = null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CodexActionButtons(
                codex = codex,
                isLikesCodex = isLikesCodex,
                searchEnabled = presentation.searchSourceOptions[codex.codexId].orEmpty().isNotEmpty(),
                state = state,
                actions = actions,
            )
            Text(
                codex.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            CodexAutomaticTagContent(
                isLikesCodex = isLikesCodex,
                automaticTags = codex.automaticTags,
                tagOptionsBySource = presentation.searchTagOptions[codex.codexId].orEmpty(),
                onSetAutomaticTag = { tag, enabled -> actions.setAutomaticTag(codex.codexId, tag, enabled) },
            )
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = { state.actionTarget = null }) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun CodexActionButtons(
    codex: Codex,
    isLikesCodex: Boolean,
    searchEnabled: Boolean,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        CodexActionIcon(Icons.Default.Download, "Download codex") {
            state.actionTarget = null; actions.downloadCodex(codex.codexId)
        }
        CodexActionIcon(Icons.Default.Share, "Share codex") {
            state.actionTarget = null; actions.shareCodex(codex.codexId)
        }
        CodexActionIcon(Icons.Default.Search, "Search from codex", searchEnabled) {
            state.searchSourceTarget = codex; state.actionTarget = null
        }
        CodexActionIcon(Icons.Default.Edit, "Rename codex") {
            state.renameTarget = codex; state.actionTarget = null
        }
        val deleteLabel = if (isLikesCodex) "Clear likes" else "Delete codex"
        CodexActionIcon(Icons.Default.Delete, deleteLabel) {
            state.deleteTarget = codex; state.actionTarget = null
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodexSourceSheet(
    codex: Codex,
    options: List<CodexSearchSourceOption>,
    state: CodexListUiState,
) {
    ModalBottomSheet(onDismissRequest = { state.searchSourceTarget = null }, dragHandle = null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                codex.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Search source", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            CodexSourceOptions(codex, options, state)
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = { state.searchSourceTarget = null }) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun CodexSourceOptions(
    codex: Codex,
    options: List<CodexSearchSourceOption>,
    state: CodexListUiState,
) {
    if (options.isEmpty()) {
        Text(
            "No searchable sources available", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
        return
    }
    options.forEach { option ->
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                state.searchSourceTarget = null
                state.tagSelectionTarget = CodexSourceSelection(codex, option.source)
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(option.source.displayName())
                val label = if (option.postCount == 1) "post" else "posts"
                Text("${option.postCount} $label", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            selectedTags = tags
                                .map { option -> option.tag }
                                .shuffled()
                                .take(randomAmount.coerceIn(0, tags.size))
                                .toSet()
                        },
                    ) {
                        Text("Randomize")
                    }
                }
                Slider(
                    value = randomAmount.coerceAtLeast(1).toFloat(),
                    onValueChange = { value ->
                        randomAmount = value.roundToInt().coerceIn(1, maxRandomAmount.coerceAtLeast(1))
                    },
                    enabled = maxRandomAmount > 1,
                    valueRange = 1f..maxRandomAmount.coerceAtLeast(2).toFloat(),
                    steps = if (maxRandomAmount > 1) maxRandomAmount - 2 else 0,
                )
            }

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
                    selectedTags = if (tag in selectedTags) {
                        selectedTags - tag
                    } else {
                        selectedTags + tag
                    }
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
private fun CodexSearchTagSelectionGrid(
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

        }

        CodexTileMetadata(
            codex = codex,
            itemCount = itemCount,
            onOpenActions = onOpenActions,
        )
    }
}
