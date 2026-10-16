package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.recents.SavedSearchNameDialog
import com.theoriacodex.app.recents.SavedSearchRow
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.SavedSearchEntry

@Composable
internal fun SavedSearchesCodexFilters(
    entries: List<SavedSearchEntry>,
    state: FeedFabRestoreState,
    onChange: (FeedFabRestoreState) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<SavedSearchEntry?>(null) }
    val excluded = state.excludedSavedSearches.toSet()
    Text("Saved searches", style = MaterialTheme.typography.titleMedium)
    Text("Include searches in this feed. Excluding a search keeps it saved.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true,
        label = { Text("Find saved search") }, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onChange(state.copy(excludedSavedSearches = emptyList())) }) { Text("Include all") }
        TextButton(onClick = { onChange(state.copy(excludedSavedSearches = entries.map { it.id })) }) { Text("Exclude all") }
    }
    val matching = remember(entries, filter) { entries.filter { it.name.contains(filter.trim(), ignoreCase = true) } }
    if (entries.isEmpty()) Text("Long-press a recent Search or For You search to name and pin it.")
    else if (matching.isEmpty()) Text("No matching saved searches")
    LazyColumn(Modifier.fillMaxWidth().height(280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(matching, key = SavedSearchEntry::id) { entry ->
            SavedSearchRow(entry,
                onOpen = {
                    val currentIds = entries.mapTo(mutableSetOf()) { it.id }
                    val next = if (entry.id in excluded) excluded - entry.id else excluded + entry.id
                    onChange(state.copy(excludedSavedSearches = next.intersect(currentIds).toList()))
                },
                onRename = { renaming = it }, onRemove = { onRemove(it.id) }, selected = entry.id !in excluded,
            )
        }
    }
    renaming?.let { entry ->
        SavedSearchNameDialog(entry.name, renaming = true, onDismiss = { renaming = null }, onConfirm = {
            onRename(entry.id, it); renaming = null
        })
    }
}
