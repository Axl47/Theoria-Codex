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
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Codex?>(null) }
    var deleteTarget by remember { mutableStateOf<Codex?>(null) }
    var actionTarget by remember { mutableStateOf<Codex?>(null) }
    var searchSourceTarget by remember { mutableStateOf<Codex?>(null) }
    var tagSelectionTarget by remember { mutableStateOf<CodexSourceSelection?>(null) }
    var reorderMode by remember { mutableStateOf(false) }

    var reorderDraft by remember { mutableStateOf(codices) }
    var draggingCodexId by remember { mutableStateOf<String?>(null) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var itemHeightPx by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(codices, reorderMode) {
        if (!reorderMode) {
            reorderDraft = codices
        } else {
            val idOrder = reorderDraft.map { it.codexId }
            reorderDraft = idOrder.mapNotNull { id -> codices.firstOrNull { it.codexId == id } }
                .let { existing ->
                    val missing = codices.filterNot { codex -> existing.any { it.codexId == codex.codexId } }
                    existing + missing
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Codex", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onImportCodex) {
                    Text("Import")
                }
                TextButton(
                    onClick = {
                        if (!reorderMode) {
                            reorderDraft = codices
                            reorderMode = true
                            return@TextButton
                        }
                        onCommitReorder(reorderDraft.map { it.codexId })
                        draggingCodexId = null
                        draggingIndex = -1
                        dragOffsetY = 0f
                        reorderMode = false
                    },
                ) {
                    Text(if (reorderMode) "Done" else "Reorder")
                }
                TextButton(onClick = { showCreateDialog = true }) {
                    Text("+ Create")
                }
            }
        }

        if (codices.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("No codices yet", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showCreateDialog = true }) {
                        Text("Create codex")
                    }
                }
            }
        } else if (reorderMode) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = draggingCodexId == null,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(reorderDraft, key = { _, codex -> codex.codexId }) { index, codex ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                if (draggingCodexId == codex.codexId) {
                                    translationY = dragOffsetY
                                    shadowElevation = 14f
                                }
                            }
                            .onSizeChanged { size ->
                                val height = size.height.toFloat()
                                if (height > 1f) {
                                    itemHeightPx = height
                                }
                            }
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = if (draggingCodexId == codex.codexId) 6.dp else 0.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(68.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center,
                            ) {
                                CodexCoverImage(
                                    candidates = codexCoverCandidates[codex.codexId].orEmpty(),
                                    contentDescription = codex.name,
                                    modifier = Modifier.fillMaxSize(),
                                    fallback = {
                                        Icon(
                                            imageVector = Icons.Default.Image,
                                            contentDescription = null,
                                        )
                                    },
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = codex.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${itemCounts[codex.codexId] ?: 0} items",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            IconButton(
                                modifier = Modifier.pointerInput(codex.codexId, itemHeightPx) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggingCodexId = codex.codexId
                                            draggingIndex = index
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggingCodexId = null
                                            draggingIndex = -1
                                            dragOffsetY = 0f
                                        },
                                        onDragEnd = {
                                            draggingCodexId = null
                                            draggingIndex = -1
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            if (draggingCodexId != codex.codexId) return@detectDragGestures
                                            change.consume()
                                            dragOffsetY += dragAmount.y

                                            val currentIndex = draggingIndex.takeIf { it >= 0 } ?: index
                                            val threshold = itemHeightPx * 0.55f

                                            if (dragOffsetY >= threshold && currentIndex < reorderDraft.lastIndex) {
                                                reorderDraft = moveCodex(reorderDraft, currentIndex, currentIndex + 1)
                                                draggingIndex = currentIndex + 1
                                                dragOffsetY -= itemHeightPx
                                            } else if (dragOffsetY <= -threshold && currentIndex > 0) {
                                                reorderDraft = moveCodex(reorderDraft, currentIndex, currentIndex - 1)
                                                draggingIndex = currentIndex - 1
                                                dragOffsetY += itemHeightPx
                                            }
                                        },
                                    )
                                },
                                onClick = {},
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DragHandle,
                                    contentDescription = "Drag to reorder",
                                )
                            }
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(codices, key = { codex -> codex.codexId }) { codex ->
                    CodexGridTile(
                        codex = codex,
                        itemCount = itemCounts[codex.codexId] ?: 0,
                        coverCandidates = codexCoverCandidates[codex.codexId].orEmpty(),
                        onOpen = { onOpenCodex(codex.codexId) },
                        onOpenActions = { actionTarget = codex },
                        onLongPress = { actionTarget = codex },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CodexNameDialog(
            title = "Create Codex",
            initialName = "",
            onDismiss = { showCreateDialog = false },
            onSave = { name ->
                onCreateCodex(name)
                showCreateDialog = false
            },
        )
    }

    val rename = renameTarget
    if (rename != null) {
        CodexNameDialog(
            title = "Rename Codex",
            initialName = rename.name,
            onDismiss = { renameTarget = null },
            onSave = { name ->
                onRenameCodex(rename.codexId, name)
                renameTarget = null
            },
        )
    }

    val delete = deleteTarget
    if (delete != null) {
        val clearsLikes = delete.codexId == likesCodexId
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (clearsLikes) "Clear Likes?" else "Delete Codex?") },
            text = {
                Text(
                    if (clearsLikes) {
                        "Clear all liked posts from this recommendation profile? The Likes Codex will remain."
                    } else {
                        "Delete \"${delete.name}\" and all saved items in it? This cannot be undone."
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteCodex(delete.codexId)
                        deleteTarget = null
                    },
                ) {
                    Text(if (clearsLikes) "Clear" else "Delete")
                }
            },
        )
    }

    val actionCodex = actionTarget?.let { target ->
        codices.firstOrNull { codex -> codex.codexId == target.codexId } ?: target
    }
    if (actionCodex != null) {
        val clearsLikes = actionCodex.codexId == likesCodexId
        val searchOptions = codexSearchSourceOptions[actionCodex.codexId].orEmpty()
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            dragHandle = null,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = {
                            actionTarget = null
                            onDownloadCodex(actionCodex.codexId)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download codex",
                        )
                    }
                    IconButton(
                        onClick = {
                            actionTarget = null
                            onShareCodex(actionCodex.codexId)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share codex",
                        )
                    }
                    IconButton(
                        enabled = searchOptions.isNotEmpty(),
                        onClick = {
                            searchSourceTarget = actionCodex
                            actionTarget = null
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search from codex",
                        )
                    }
                    IconButton(
                        onClick = {
                            renameTarget = actionCodex
                            actionTarget = null
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Rename codex",
                        )
                    }
                    IconButton(
                        onClick = {
                            deleteTarget = actionCodex
                            actionTarget = null
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = if (clearsLikes) "Clear likes" else "Delete codex",
                        )
                    }
                }
                Text(
                    text = actionCodex.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                CodexAutomaticTagContent(
                    isLikesCodex = clearsLikes,
                    automaticTags = actionCodex.automaticTags,
                    tagOptionsBySource = codexSearchTagOptions[actionCodex.codexId].orEmpty(),
                    onSetAutomaticTag = { tag, enabled ->
                        onSetAutomaticTag(actionCodex.codexId, tag, enabled)
                    },
                )
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { actionTarget = null },
                ) {
                    Text("Cancel")
                }
            }
        }
    }

    val searchCodex = searchSourceTarget
    if (searchCodex != null) {
        val sourceOptions = codexSearchSourceOptions[searchCodex.codexId].orEmpty()
        ModalBottomSheet(
            onDismissRequest = { searchSourceTarget = null },
            dragHandle = null,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = searchCodex.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Search source",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (sourceOptions.isEmpty()) {
                    Text(
                        text = "No searchable sources available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    )
                } else {
                    sourceOptions.forEach { option ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                searchSourceTarget = null
                                tagSelectionTarget = CodexSourceSelection(
                                    codex = searchCodex,
                                    source = option.source,
                                )
                            },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(option.source.displayName())
                                Text(
                                    text = "${option.postCount} ${if (option.postCount == 1) "post" else "posts"}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { searchSourceTarget = null },
                ) {
                    Text("Cancel")
                }
            }
        }
    }

    val tagSelection = tagSelectionTarget
    if (tagSelection != null) {
        val tagOptions = codexSearchTagOptions[tagSelection.codex.codexId]
            ?.get(tagSelection.source)
            .orEmpty()
        CodexSearchTagSelectionSheet(
            selection = tagSelection,
            tags = tagOptions,
            onDismiss = { tagSelectionTarget = null },
            onApply = { includeTags ->
                tagSelectionTarget = null
                onSearchFromCodex(tagSelection.codex.codexId, tagSelection.source, includeTags)
            },
        )
    }
}

private data class CodexSourceSelection(
    val codex: Codex,
    val source: SourceKey,
)

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

private fun moveCodex(
    codices: List<Codex>,
    fromIndex: Int,
    toIndex: Int,
): List<Codex> {
    if (fromIndex == toIndex) return codices
    if (fromIndex !in codices.indices || toIndex !in codices.indices) return codices

    val mutable = codices.toMutableList()
    val moved = mutable.removeAt(fromIndex)
    mutable.add(toIndex, moved)
    return mutable
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
