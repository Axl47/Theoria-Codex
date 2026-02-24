package com.theoriacodex.app.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    coordinator: SearchCoordinator,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var showFilterSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    LaunchedEffect(coordinator.draftQuery.mode) {
        coordinator.loadTrendingTags()
    }

    val autocompleteSuggestions = remember(input, coordinator.trendingTags) {
        if (input.trim().isBlank()) {
            emptyList()
        } else {
            coordinator.trendingTags
                .filter { suggestion -> suggestion.text.contains(input.trim(), ignoreCase = true) }
                .take(10)
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showFilterSheet = true }) {
                Icon(Icons.Default.FilterList, contentDescription = "Filter and sort")
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
                label = { Text("Search tags") },
                supportingText = { Text("Use '-tag' to add exclusion") },
                trailingIcon = {
                    TextButton(onClick = {
                        coordinator.addTagInput(input)
                        input = ""
                    }) {
                        Text("Add")
                    }
                }
            )

            if (autocompleteSuggestions.isNotEmpty()) {
                AutocompletePanel(
                    suggestions = autocompleteSuggestions,
                    onInclude = { tag ->
                        coordinator.addIncludeTag(tag)
                        input = ""
                    },
                    onExclude = { tag ->
                        coordinator.addExcludeTag(tag)
                        input = ""
                    },
                )
            }

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
                        EmptyBlock(hasPendingChanges = coordinator.hasPendingChanges)
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
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(post.id.source.name) },
                                    )
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

    if (showFilterSheet) {
        FilterSheet(
            coordinator = coordinator,
            onDismiss = {
                scope.launch {
                    sheetState.hide()
                    showFilterSheet = false
                }
            },
            sheetState = sheetState,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    coordinator: SearchCoordinator,
    onDismiss: () -> Unit,
    sheetState: androidx.compose.material3.SheetState,
) {
    var minScoreInput by remember(coordinator.draftQuery.minScore) {
        mutableStateOf(coordinator.draftQuery.minScore?.toString().orEmpty())
    }
    var selectedPreset by remember(coordinator.draftQuery.dateRange) {
        mutableStateOf(inferPreset(coordinator.draftQuery.dateRange?.fromEpochMs, coordinator.draftQuery.dateRange?.toEpochMs))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Sort", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SortMode.entries.size) { index ->
                    val mode = SortMode.entries[index]
                    FilterChip(
                        selected = coordinator.draftQuery.sort == mode,
                        onClick = { coordinator.setSort(mode) },
                        label = { Text(mode.name) },
                    )
                }
            }

            HorizontalDivider()
            Text("Date range", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DateRangePreset.entries.size) { index ->
                    val preset = DateRangePreset.entries[index]
                    val label = when (preset) {
                        DateRangePreset.NONE -> "None"
                        DateRangePreset.TODAY -> "Today"
                        DateRangePreset.LAST_7_DAYS -> "7d"
                        DateRangePreset.LAST_30_DAYS -> "30d"
                    }
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = {
                            selectedPreset = preset
                            coordinator.setDateRangePreset(preset)
                        },
                        label = { Text(label) },
                    )
                }
            }

            HorizontalDivider()
            Text("Min score", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = minScoreInput,
                onValueChange = { value ->
                    minScoreInput = value.filter { it.isDigit() }
                    coordinator.setMinScore(minScoreInput.toIntOrNull())
                },
                label = { Text("Optional") },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = {
                    coordinator.resetFilters()
                    selectedPreset = DateRangePreset.NONE
                    minScoreInput = ""
                }) {
                    Text("Reset")
                }
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun AutocompletePanel(
    suggestions: List<TagSuggestion>,
    onInclude: (String) -> Unit,
    onExclude: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(suggestions.size) { index ->
                val item = suggestions[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = item.text, style = MaterialTheme.typography.bodyMedium)
                        val meta = listOfNotNull(item.type, item.count?.toString()).joinToString(" • ")
                        if (meta.isNotBlank()) {
                            Text(text = meta, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onInclude(item.text) }) {
                            Text("+")
                        }
                        TextButton(onClick = { onExclude(item.text) }) {
                            Text("-")
                        }
                    }
                }
                if (index != suggestions.lastIndex) {
                    HorizontalDivider()
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
private fun EmptyBlock(hasPendingChanges: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (hasPendingChanges) {
                "Draft updated. Press Apply to refresh results."
            } else {
                "No results yet. Add tags or use Explore quick queries."
            },
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

private fun inferPreset(fromEpochMs: Long?, toEpochMs: Long?): DateRangePreset {
    if (fromEpochMs == null || toEpochMs == null) {
        return DateRangePreset.NONE
    }
    val spanMs = abs(toEpochMs - fromEpochMs)
    val dayMs = 24L * 60L * 60L * 1000L
    return when {
        spanMs <= dayMs + (2L * 60L * 60L * 1000L) -> DateRangePreset.TODAY
        spanMs <= 7L * dayMs + (2L * 60L * 60L * 1000L) -> DateRangePreset.LAST_7_DAYS
        spanMs <= 30L * dayMs + (2L * 60L * 60L * 1000L) -> DateRangePreset.LAST_30_DAYS
        else -> DateRangePreset.NONE
    }
}
