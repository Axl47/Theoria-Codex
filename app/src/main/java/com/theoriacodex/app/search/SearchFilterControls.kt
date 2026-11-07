package com.theoriacodex.app.search

import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDateRangePickerState
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.FilterSection
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.SortMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import com.theoriacodex.app.ui.components.SourceIconMenu
import com.theoriacodex.app.ui.components.SourceScopeIndicator
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.domain.model.QueryMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.ANIMATED_DURATION_MAX_BUCKET
import com.theoriacodex.app.media.ANIMATED_DURATION_MIN_BUCKET
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.animatedDurationBucketLabel
import com.theoriacodex.app.media.animatedDurationRangeLabel
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.tags.FavoriteTagActionGrid
import com.theoriacodex.data.repository.FavoriteTagEntry
import com.theoriacodex.domain.model.SourceKey
import kotlin.math.roundToInt

@Composable
fun AnimatedDurationRangeControl(
    range: AnimatedDurationRange,
    onRangeChange: (AnimatedDurationRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Animated duration", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (range.isFullRange) "Any" else animatedDurationRangeLabel(range),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RangeSlider(
            value = range.normalizedMinBucket.toFloat()..range.normalizedMaxBucket.toFloat(),
            onValueChange = { values ->
                val minBucket = values.start.roundToInt()
                    .coerceIn(ANIMATED_DURATION_MIN_BUCKET, ANIMATED_DURATION_MAX_BUCKET)
                val maxBucket = values.endInclusive.roundToInt()
                    .coerceIn(ANIMATED_DURATION_MIN_BUCKET, ANIMATED_DURATION_MAX_BUCKET)
                onRangeChange(AnimatedDurationRange(minOf(minBucket, maxBucket), maxOf(minBucket, maxBucket)))
            },
            valueRange = ANIMATED_DURATION_MIN_BUCKET.toFloat()..ANIMATED_DURATION_MAX_BUCKET.toFloat(),
            steps = (ANIMATED_DURATION_MAX_BUCKET - ANIMATED_DURATION_MIN_BUCKET - 1).coerceAtLeast(0),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DurationEndpointLabel(ANIMATED_DURATION_MIN_BUCKET)
            DurationEndpointLabel(ANIMATED_DURATION_MAX_BUCKET)
        }
    }
}

@Composable
private fun DurationEndpointLabel(bucket: Int) {
    Text(
        text = animatedDurationBucketLabel(bucket),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoriteTagSheet(
    sections: List<FavoriteTagSection>,
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    emptyMessage: String,
    onAddTag: (FavoriteTagEntry) -> Unit,
    onRemoveTag: (SourceKey, List<String>) -> Unit,
    onRenameTag: (FavoriteTagEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    ExpandedBottomSheet(onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Saved tags", style = MaterialTheme.typography.titleLarge)
            // What each kind does, since saved searches (which run) sit beside these in the composer.
            Text(
                "Saved tags add to the search you're building. A single tag is added on its own; an " +
                    "“or” group matches posts with any of its tags, and can be named. Save a tag by " +
                    "holding it in a post's info, or a group from the composer. Some sites also offer " +
                    "a built-in Animated group.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sections.isEmpty()) {
                Text(emptyMessage, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                sections.forEachIndexed { index, section ->
                    FavoriteTagSectionContent(
                        section, tagVideoCountProvider, fetchTagVideoCounts, onAddTag, onRemoveTag, onRenameTag,
                    )
                    if (index != sections.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun FavoriteTagSectionContent(
    section: FavoriteTagSection,
    tagVideoCountProvider: (SourceKey, String) -> Int?,
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?>,
    onAddTag: (FavoriteTagEntry) -> Unit,
    onRemoveTag: (SourceKey, List<String>) -> Unit,
    onRenameTag: (FavoriteTagEntry) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(section.source.displayName(), style = MaterialTheme.typography.titleSmall)
        FavoriteTagActionGrid(
            source = section.source,
            entries = section.entries,
            tagVideoCountProvider = tagVideoCountProvider,
            fetchTagVideoCounts = fetchTagVideoCounts,
            onAddTag = onAddTag,
            onRemoveTag = { entry -> onRemoveTag(section.source, entry.tags) },
            onRenameTag = onRenameTag,
        )
    }
}

internal fun sortModeLabel(sortMode: SortMode): String = when (sortMode) {
    SortMode.NEWEST -> "Newest"
    SortMode.POPULAR -> "Popular"
    SortMode.TOP -> "Top"
    SortMode.RANDOM -> "Random"
}

/** Quick ranges plus a calendar. Picker values and local filtering both read dates in UTC. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateRangeFilterSection(range: DateRange?, onPreset: (DateRangePreset) -> Unit, onCustom: (DateRange) -> Unit) {
    val now = remember { System.currentTimeMillis() }
    val preset = presetFor(range, now)
    var picking by remember { mutableStateOf(false) }
    FilterSection("Date") {
        DateRangePreset.entries.forEach { option ->
            FilterChoiceChip(option.label, preset == option) { onPreset(option) }
        }
        val customLabel = range?.takeIf { preset == null }?.let { dateRangeLabel(it, now) } ?: "Custom"
        FilterChoiceChip(customLabel, preset == null) { picking = true }
    }
    if (!picking) return
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = range?.fromEpochMs?.takeIf { preset == null },
        initialSelectedEndDateMillis = range?.toEpochMs?.takeIf { preset == null },
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= now
        },
    )
    DatePickerDialog(
        onDismissRequest = { picking = false },
        dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedEndDateMillis != null,
                onClick = {
                    val from = pickerState.selectedStartDateMillis
                    val to = pickerState.selectedEndDateMillis
                    if (from != null && to != null) onCustom(customDateRange(from, to))
                    picking = false
                },
            ) { Text("Apply") }
        },
    ) {
        DateRangePicker(state = pickerState, modifier = Modifier.weight(1f))
    }
}

/** The current scope stays visible; holding menu sources edits the Unified source group. */
@Composable
internal fun SearchSourcePicker(
    draftSourceScope: SearchSourceScope,
    options: List<QueryMode>,
    unifiedSourceCount: Int,
    unifiedSources: Set<SourceKey> = emptySet(),
    onOpen: () -> Unit = {},
    onModeSelected: (QueryMode) -> Unit,
    onTemporarySourceToggled: (SourceKey) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val highlightedSources = if (draftSourceScope == SearchSourceScope.GlobalUnified) {
        unifiedSources
    } else {
        draftSourceScope.explicitSources
    }
    val scopeLabel = when (draftSourceScope) {
        SearchSourceScope.GlobalUnified -> "Unified, $unifiedSourceCount sources"
        is SearchSourceScope.Single -> draftSourceScope.source.displayName()
        is SearchSourceScope.Temporary -> "Unified, ${draftSourceScope.sources.size} sources"
    }
    val selectedSource = (draftSourceScope as? SearchSourceScope.Single)?.source
    Box {
        TextButton(
            modifier = Modifier.semantics { contentDescription = "Search source: $scopeLabel" },
            onClick = {
                onOpen()
                expanded = true
            },
        ) {
            Box(Modifier.clearAndSetSemantics {}) {
                SourceScopeIndicator(
                    selectedSource,
                    (draftSourceScope as? SearchSourceScope.Temporary)?.sources?.size ?: unifiedSourceCount,
                )
            }
        }
        SourceIconMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            options = options.filterIsInstance<QueryMode.Source>().map { it.source } - selectedSource,
            highlightedSources = highlightedSources,
            combinedLabel = "Unified",
            onSelect = { source ->
                expanded = false
                onModeSelected(source?.let(QueryMode::Source) ?: QueryMode.Unified)
            },
            onLongPress = onTemporarySourceToggled,
        )
    }
}
