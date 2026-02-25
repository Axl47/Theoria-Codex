package com.theoriacodex.app.codex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.Codex

@Composable
fun CodexListScreen(
    codices: List<Codex>,
    itemCounts: Map<String, Int>,
    onOpenCodex: (String) -> Unit,
    onCreateCodex: (String) -> Unit,
    onRenameCodex: (String, String) -> Unit,
    onDeleteCodex: (String) -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Codex?>(null) }

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
            TextButton(onClick = { showCreateDialog = true }) {
                Text("+ Create")
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
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(codices, key = { it.codexId }) { codex ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenCodex(codex.codexId) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
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
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { renameTarget = codex }) { Text("Rename") }
                                TextButton(onClick = { onDeleteCodex(codex.codexId) }) { Text("Delete") }
                            }
                        }
                    }
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
