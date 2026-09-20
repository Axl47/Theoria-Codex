package com.theoriacodex.app.recents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.data.repository.MAX_SAVED_SEARCH_NAME_LENGTH
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.app.source.displayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedSearchSheet(
    searches: List<SavedSearchEntry>,
    onDismiss: () -> Unit,
    onOpen: (SavedSearchEntry) -> Unit,
    onRename: (SavedSearchEntry) -> Unit,
    onRemove: (SavedSearchEntry) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Saved searches", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Available across profiles. Clearing Recents keeps these searches.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (searches.isEmpty()) {
                        Text("Long-press a recent Search or For You search to name and pin it.")
                    }
                }
            }
            items(searches, key = SavedSearchEntry::id) { entry ->
                SavedSearchRow(entry, onOpen, onRename, onRemove)
            }
        }
    }
}

@Composable
private fun SavedSearchRow(
    entry: SavedSearchEntry,
    onOpen: (SavedSearchEntry) -> Unit,
    onRename: (SavedSearchEntry) -> Unit,
    onRemove: (SavedSearchEntry) -> Unit,
) {
    val presentation = recentSearchPresentation(entry.search)
    val context = when (entry.search.kind) {
        RecentSearchKind.SOURCE -> entry.search.sources.single().displayName()
        RecentSearchKind.UNIFIED -> "Unified"
        RecentSearchKind.MULTI_SEARCH -> "Multi-Search"
        RecentSearchKind.FYP -> "For You"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f).clickable { onOpen(entry) }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "$context · ${presentation.title}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onRename(entry) }) {
                Icon(Icons.Default.Edit, contentDescription = "Rename ${entry.name}")
            }
            IconButton(onClick = { onRemove(entry) }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove ${entry.name}")
            }
        }
    }
}

@Composable
internal fun SavedSearchNameDialog(
    initialName: String,
    renaming: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (renaming) "Rename search" else "Pin search") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= MAX_SAVED_SEARCH_NAME_LENGTH) name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(if (renaming) "Save" else "Pin")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
