package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.theoriacodex.app.source.displayName
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.data.repository.followKey

@Composable
internal fun FollowedCodexFilters(
    follows: List<FollowedCreator>,
    state: FeedFabRestoreState,
    onChange: (FeedFabRestoreState) -> Unit,
    onManage: () -> Unit,
) {
    Text("Source", style = MaterialTheme.typography.titleMedium)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(selected = state.followedSources.isEmpty(),
                onClick = { onChange(state.copy(followedSources = emptyList())) }, label = { Text("All") })
        }
        items(follows.map { it.creator.source }.distinct(), key = { it.name }) { source ->
            FilterChip(selected = source.name in state.followedSources,
                onClick = { onChange(state.copy(followedSources = state.followedSources.toggled(source.name))) },
                label = { Text(source.displayName()) })
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text("Authors", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onManage) { Text("Manage") }
    }
    var query by remember { mutableStateOf("") }
    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
        label = { Text("Find author") }, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { onChange(state.copy(followedAuthors = emptyList())) }) {
        Text(if (state.followedAuthors.isEmpty()) "All authors" else "All authors (${state.followedAuthors.size} selected)")
    }
    val options = remember(follows, state.followedSources, query) {
        follows.filter {
            (state.followedSources.isEmpty() || it.creator.source.name in state.followedSources) &&
                it.creator.displayName.contains(query.trim(), ignoreCase = true)
        }
    }
    if (options.isEmpty()) Text("No matching authors")
    // The sheet owns vertical scrolling; author rows must not create a nested scroll surface.
    FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 2,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { follow ->
            val key = follow.creator.followKey()
            val selected = key in state.followedAuthors
            Row(Modifier.weight(1f).testTag("followed-author:$key")
                .toggleable(value = selected, role = Role.Checkbox,
                    onValueChange = { onChange(state.copy(followedAuthors = state.followedAuthors.toggled(key))) }),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = null)
                Text(follow.creator.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (options.size % 2 != 0) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
    }
}

private fun List<String>.toggled(value: String): List<String> = if (value in this) this - value else this + value
