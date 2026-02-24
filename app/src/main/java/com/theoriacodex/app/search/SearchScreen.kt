package com.theoriacodex.app.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    coordinator: SearchCoordinator,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        coordinator.loadTrendingTags()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showSortMenu = true }) {
                Icon(Icons.Default.FilterList, contentDescription = "Sort")
            }
            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                SortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.name) },
                        onClick = {
                            coordinator.setSort(mode)
                            showSortMenu = false
                        }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = input,
                onValueChange = { input = it },
                label = { Text("Add tag (prefix '-' to exclude)") },
                trailingIcon = {
                    TextButton(onClick = {
                        coordinator.addTagInput(input)
                        input = ""
                    }) {
                        Text("Add")
                    }
                }
            )

            ModeRow(
                mode = coordinator.draftQuery.mode,
                onModeSelected = coordinator::setMode,
            )

            TagRow(
                includeTags = coordinator.draftQuery.includeTags,
                excludeTags = coordinator.draftQuery.excludeTags,
                onRemoveInclude = coordinator::removeIncludeTag,
                onRemoveExclude = coordinator::removeExcludeTag,
            )

            if (coordinator.statuses.isNotEmpty() && coordinator.appliedQuery.mode == QueryMode.Unified) {
                StatusRow(coordinator = coordinator)
            }

            when {
                coordinator.loading -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                coordinator.errorMessage != null -> {
                    Box(modifier = Modifier.weight(1f)) {
                        ErrorBlock(
                            message = coordinator.errorMessage.orEmpty(),
                            onRetry = { scope.launch { coordinator.retry() } },
                        )
                    }
                }
                coordinator.results.isEmpty() -> {
                    Box(modifier = Modifier.weight(1f)) {
                        EmptyBlock()
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(coordinator.results) { post ->
                            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(text = post.id.source.name, style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        text = post.id.sourcePostId,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                    )
                                    post.canonicalTags.firstOrNull()?.let { firstTag ->
                                        Text(text = "#$firstTag", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (coordinator.hasPendingChanges) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { coordinator.resetDraft() }) {
                        Text("Reset")
                    }
                    TextButton(onClick = { scope.launch { coordinator.applyDraft() } }) {
                        Text("Apply")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(
    mode: QueryMode,
    onModeSelected: (QueryMode) -> Unit,
) {
    val options = listOf(
        QueryMode.Unified,
        QueryMode.Source(SourceKey.PIXIV),
        QueryMode.Source(SourceKey.GELBOORU),
        QueryMode.Source(SourceKey.AIBOORU),
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { index ->
            val option = options[index]
            FilterChip(
                selected = option == mode,
                onClick = { onModeSelected(option) },
                label = {
                    val label = when (option) {
                        QueryMode.Unified -> "Unified"
                        is QueryMode.Source -> option.source.name
                    }
                    Text(label)
                }
            )
        }
    }
}

@Composable
private fun TagRow(
    includeTags: List<String>,
    excludeTags: List<String>,
    onRemoveInclude: (String) -> Unit,
    onRemoveExclude: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(includeTags.size) { index ->
            val tag = includeTags[index]
            AssistChip(
                onClick = { onRemoveInclude(tag) },
                label = { Text(tag) }
            )
        }
        items(excludeTags.size) { index ->
            val tag = excludeTags[index]
            AssistChip(
                onClick = { onRemoveExclude(tag) },
                label = { Text("-$tag") }
            )
        }
    }
}

@Composable
private fun StatusRow(coordinator: SearchCoordinator) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(coordinator.statuses.size) { index ->
            val status = coordinator.statuses[index]
            val text = when (status.state) {
                SourceRunState.SUCCESS -> "${status.source.name} OK"
                SourceRunState.EXCLUDED -> "${status.source.name} excluded"
                SourceRunState.FAILED -> "${status.source.name} failed"
            }
            AssistChip(onClick = {}, label = { Text(text) })
        }
    }
}

@Composable
private fun EmptyBlock() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "No results yet. Add tags and press Apply.",
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorBlock(
    message: String,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Could not load results", style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodySmall)
            Text(
                text = "Retry",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onRetry),
            )
        }
    }
}
