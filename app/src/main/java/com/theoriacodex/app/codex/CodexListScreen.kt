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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theoriacodex.app.source.displayName
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.SourceKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexListScreen(
    codices: List<Codex>,
    itemCounts: Map<String, Int>,
    codexCoverModels: Map<String, Any?>,
    codexSearchSourceOptions: Map<String, List<CodexSearchSourceOption>>,
    onOpenCodex: (String) -> Unit,
    onImportCodex: () -> Unit,
    onDownloadCodex: (String) -> Unit,
    onShareCodex: (String) -> Unit,
    onSearchFromCodex: (String, SourceKey) -> Unit,
    onCommitReorder: (List<String>) -> Unit,
    onCreateCodex: (String) -> Unit,
    onRenameCodex: (String, String) -> Unit,
    onDeleteCodex: (String) -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Codex?>(null) }
    var deleteTarget by remember { mutableStateOf<Codex?>(null) }
    var actionTarget by remember { mutableStateOf<Codex?>(null) }
    var searchSourceTarget by remember { mutableStateOf<Codex?>(null) }
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
                                val cover = codexCoverModels[codex.codexId]
                                if (cover != null) {
                                    AsyncImage(
                                        model = cover,
                                        contentDescription = codex.name,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = null,
                                    )
                                }
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
                        coverModel = codexCoverModels[codex.codexId],
                        onOpen = { onOpenCodex(codex.codexId) },
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
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Codex?") },
            text = {
                Text("Delete \"${delete.name}\" and all saved items in it? This cannot be undone.")
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
                    Text("Delete")
                }
            },
        )
    }

    val actionCodex = actionTarget
    if (actionCodex != null) {
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
                            contentDescription = "Delete codex",
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
                                onSearchFromCodex(searchCodex.codexId, option.source)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodexGridTile(
    codex: Codex,
    itemCount: Int,
    coverModel: Any?,
    onOpen: () -> Unit,
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
            if (coverModel != null) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = codex.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
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
            }

        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = codex.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$itemCount items",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CodexNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initialName) { mutableStateOf(initialName) }
    fun saveIfValid() {
        val trimmed = value.trim()
        if (trimmed.isNotBlank()) {
            onSave(trimmed)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = value,
                onValueChange = { value = it.replace("\n", " ") },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { saveIfValid() },
                ),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        confirmButton = {
            TextButton(onClick = { saveIfValid() }) {
                Text("Save")
            }
        },
    )
}
