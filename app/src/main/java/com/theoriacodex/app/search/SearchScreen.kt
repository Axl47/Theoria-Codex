package com.theoriacodex.app.search

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    coordinator: SearchCoordinator,
    onOpenViewer: (List<Post>, ViewerLaunchContext) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var searchFieldFocused by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyStaggeredGridState()
    val queryHash = coordinator.appliedQueryHash

    LaunchedEffect(coordinator.draftQuery.mode) {
        coordinator.loadTrendingTags()
    }

    LaunchedEffect(queryHash, coordinator.results.size) {
        val restored = coordinator.restoreSearchScrollState() ?: return@LaunchedEffect
        if (coordinator.results.isNotEmpty()) {
            val lastIndex = coordinator.results.lastIndex.coerceAtLeast(0)
            gridState.scrollToItem(
                index = restored.firstVisibleItemIndex.coerceIn(0, lastIndex),
                scrollOffset = restored.firstVisibleItemOffsetPx.coerceAtLeast(0),
            )
        }
    }

    LaunchedEffect(queryHash, coordinator.results.size) {
        if (coordinator.results.isEmpty()) return@LaunchedEffect
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) ->
                coordinator.persistSearchScrollState(index = index, offsetPx = offset)
            }
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
    val showSearchControls = searchFieldFocused ||
        input.isNotBlank() ||
        autocompleteSuggestions.isNotEmpty() ||
        coordinator.hasPendingChanges

    fun commitTagInput() {
        coordinator.addTagInput(input)
        input = ""
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                modifier = Modifier.padding(bottom = 72.dp),
                onClick = { showFilterSheet = true },
            ) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        searchFieldFocused = state.isFocused
                    },
                value = input,
                onValueChange = { input = it },
                label = { Text("Search tags") },
                supportingText = { Text("Use '-tag' to add exclusion") },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                    keyboardType = KeyboardType.Text,
                    capitalization = KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { commitTagInput() },
                ),
                trailingIcon = {
                    TextButton(onClick = { commitTagInput() }) {
                        Text("Add")
                    }
                }
            )

            AnimatedVisibility(
                visible = showSearchControls,
                enter = slideInVertically(initialOffsetY = { fullHeight -> fullHeight / 4 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { fullHeight -> fullHeight / 5 }) + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        options = coordinator.modeOptions,
                        onModeSelected = coordinator::setMode,
                    )
                    if (coordinator.draftQuery.mode == QueryMode.Unified) {
                        Text(
                            text = "(${coordinator.enabledSourceCount} sources enabled)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    TagRow(
                        includeTags = coordinator.draftQuery.includeTags,
                        excludeTags = coordinator.draftQuery.excludeTags,
                        onRemoveInclude = coordinator::removeIncludeTag,
                        onRemoveExclude = coordinator::removeExcludeTag,
                    )

                    if (
                        coordinator.appliedQuery.mode == QueryMode.Unified &&
                        coordinator.statuses.any { it.state != SourceRunState.SUCCESS }
                    ) {
                        StatusRow(coordinator = coordinator)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            onClick = {
                                coordinator.resetDraft()
                                input = ""
                                showFilterSheet = false
                            },
                            enabled = coordinator.hasPendingChanges,
                        ) {
                            Text("Reset")
                        }
                        TextButton(onClick = { scope.launch { coordinator.applyDraft() } }) {
                            Text("Apply")
                        }
                    }
                }
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
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(2),
                        state = gridState,
                        modifier = Modifier.weight(1f),
                        verticalItemSpacing = 6.dp,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(coordinator.results) { index, post ->
                            SearchResultCard(
                                post = post,
                                onClick = {
                                    val context = coordinator.buildViewerLaunchContext(
                                        startIndex = index,
                                        scrollOffsetHint = gridState.firstVisibleItemScrollOffset,
                                    )
                                    scope.launch { coordinator.setViewerLaunchContext(context) }
                                    onOpenViewer(coordinator.results, context)
                                },
                            )
                        }
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

@Composable
private fun SearchResultCard(
    post: Post,
    onClick: () -> Unit,
) {
    val context = LocalContext.current

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        val title = post.title?.takeIf { it.isNotBlank() } ?: "Untitled"
        val previewUrl = post.preview.url ?: post.full?.url
        val ratio = previewAspectRatio(post)
        val imageModel = remember(context, previewUrl, post.id.source) {
            previewUrl?.let { buildImageRequest(context, it, post.id.source) }
        }

        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No preview",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AssistChip(onClick = {}, label = { Text(post.id.source.name) })
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            post.canonicalTags.firstOrNull()?.let { firstTag ->
                Text(text = "#$firstTag", style = MaterialTheme.typography.bodySmall)
            }
        }
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
    options: List<QueryMode>,
    onModeSelected: (QueryMode) -> Unit,
) {
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
    val filtered = coordinator.statuses.filter { it.state != SourceRunState.SUCCESS }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(filtered.size) { index ->
            val status = filtered[index]
            val text = when (status.state) {
                SourceRunState.EXCLUDED -> "${status.source.name} excluded"
                SourceRunState.FAILED -> {
                    val reason = status.failureReason?.name ?: "UNKNOWN"
                    "${status.source.name} failed ($reason)"
                }
                SourceRunState.SUCCESS -> "${status.source.name} OK"
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

private fun previewAspectRatio(post: Post): Float {
    val width = post.width ?: return 1f
    val height = post.height ?: return 1f
    if (width <= 0 || height <= 0) return 1f
    return width.toFloat() / height.toFloat()
}

private fun buildImageRequest(
    context: Context,
    url: String,
    sourceKey: SourceKey,
): ImageRequest {
    val builder = ImageRequest.Builder(context).data(url).crossfade(true)
    if (sourceKey == SourceKey.PIXIV) {
        builder
            .addHeader("Referer", "https://www.pixiv.net/")
            .addHeader("User-Agent", "Mozilla/5.0")
    }
    return builder.build()
}
