package com.theoriacodex.app.codex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theoriacodex.domain.model.Codex

@Composable
fun CodexListScreen(
    codices: List<Codex>,
    itemCounts: Map<String, Int>,
    codexCoverModels: Map<String, Any?>,
    onOpenCodex: (String) -> Unit,
    onSearchFromCodex: (String) -> Unit,
    onCommitReorder: (List<String>) -> Unit,
    onCreateCodex: (String) -> Unit,
    onRenameCodex: (String, String) -> Unit,
    onDeleteCodex: (String) -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Codex?>(null) }
    var deleteTarget by remember { mutableStateOf<Codex?>(null) }
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
                            .pointerInput(reorderDraft, itemHeightPx, draggingCodexId) {
                                detectDragGesturesAfterLongPress(
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
                                        if (draggingCodexId != codex.codexId) return@detectDragGesturesAfterLongPress
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                        val baseIndex = draggingIndex.takeIf { it >= 0 } ?: index
                                        val delta = (dragOffsetY / itemHeightPx).toInt()
                                        val target = (baseIndex + delta).coerceIn(0, reorderDraft.lastIndex)
                                        if (target != baseIndex) {
                                            val moved = reorderDraft.toMutableList()
                                            val item = moved.removeAt(baseIndex)
                                            moved.add(target, item)
                                            reorderDraft = moved
                                            draggingIndex = target
                                            dragOffsetY = 0f
                                        }
                                    },
                                )
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

                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = "Drag to reorder",
                            )
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
                        onSearch = { onSearchFromCodex(codex.codexId) },
                        onRename = { renameTarget = codex },
                        onDelete = { deleteTarget = codex },
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
}

@Composable
private fun CodexGridTile(
    codex: Codex,
    itemCount: Int,
    coverModel: Any?,
    onOpen: () -> Unit,
    onSearch: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
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

            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            ) {
                Icon(
                    imageVector = Icons.Default.Reorder,
                    contentDescription = null,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 2.dp),
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

            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                IconButton(onClick = onSearch) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search from codex",
                    )
                }
                IconButton(onClick = onRename) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Rename codex",
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete codex",
                    )
                }
            }
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
