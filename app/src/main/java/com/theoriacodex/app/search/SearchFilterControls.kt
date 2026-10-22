package com.theoriacodex.app.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.theoriacodex.app.source.SourceLogo
import com.theoriacodex.app.source.isRule34Family
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
import androidx.compose.material3.ModalBottomSheet
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
    onAddTag: (String) -> Unit,
    onRemoveTag: (SourceKey, String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Favorite Tags", style = MaterialTheme.typography.titleMedium)
            if (sections.isEmpty()) {
                Text(emptyMessage, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                sections.forEachIndexed { index, section ->
                    FavoriteTagSectionContent(
                        section, tagVideoCountProvider, fetchTagVideoCounts, onAddTag, onRemoveTag,
                    )
                    if (index != sections.lastIndex) HorizontalDivider()
                }
            }
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = onDismiss) { Text("Done") }
        }
    }
}

@Composable
private fun FavoriteTagSectionContent(
    section: FavoriteTagSection,
    tagVideoCountProvider: (SourceKey, String) -> Int?,
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (SourceKey, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(section.source.displayName(), style = MaterialTheme.typography.titleSmall)
        FavoriteTagActionGrid(
            source = section.source,
            tags = section.tags,
            tagVideoCountProvider = tagVideoCountProvider,
            fetchTagVideoCounts = fetchTagVideoCounts,
            onAddTag = onAddTag,
            onRemoveTag = { tag -> onRemoveTag(section.source, tag) },
        )
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
    var r34Expanded by remember { mutableStateOf(false) }
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
    Box {
        TextButton(
            modifier = Modifier.semantics { contentDescription = "Search source: $scopeLabel" },
            onClick = {
                onOpen()
                r34Expanded = false
                expanded = true
            },
        ) {
            Box(Modifier.clearAndSetSemantics {}) {
                SearchSourceIndicator(draftSourceScope, unifiedSourceCount)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = if (r34Expanded) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
            shadowElevation = if (r34Expanded) 8.dp else 0.dp,
            tonalElevation = 0.dp,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(if (r34Expanded) 0.dp else 6.dp)) {
                SearchSourceMenuItems(
                    options, draftSourceScope, highlightedSources, r34Expanded,
                    onR34ExpandedChange = { r34Expanded = it },
                    onModeSelected = { expanded = false; onModeSelected(it) },
                    onTemporarySourceToggled = onTemporarySourceToggled,
                )
            }
        }
    }
}

@Composable
private fun SearchSourceMenuItems(
    options: List<QueryMode>,
    draftSourceScope: SearchSourceScope,
    highlightedSources: Collection<SourceKey>,
    r34Expanded: Boolean,
    onR34ExpandedChange: (Boolean) -> Unit,
    onModeSelected: (QueryMode) -> Unit,
    onTemporarySourceToggled: (SourceKey) -> Unit,
) {
    val selectedSource = (draftSourceScope as? SearchSourceScope.Single)?.source
    val sourceOptions = options.filterIsInstance<QueryMode.Source>().filter { it.source != selectedSource }
    val r34Options = sourceOptions.filter { it.source.isRule34Family() }
    if (r34Expanded) {
        IconButton(onClick = { onR34ExpandedChange(false) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to sources")
        }
    }
    val visibleOptions = if (r34Expanded) r34Options else sourceOptions - r34Options.toSet()
    visibleOptions.forEach { option ->
        SourceMenuOption(
            option = option,
            draftSourceScope = draftSourceScope,
            highlightedSources = highlightedSources,
            showTitle = r34Expanded,
            onModeSelected = onModeSelected,
            onTemporarySourceToggled = onTemporarySourceToggled,
        )
    }
    if (!r34Expanded && r34Options.isNotEmpty()) {
        val selected = highlightedSources.any { it.isRule34Family() }
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 48.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                )
                .clickable { onR34ExpandedChange(true) }
                .semantics {
                    contentDescription = "R34"
                    this.selected = selected
                },
            contentAlignment = Alignment.Center,
        ) {
            SearchLetterIcon("R34")
        }
    }
}

/** Compact visual counterpart to the picker button's accessible scope label. */
@Composable
private fun SearchSourceIndicator(draftSourceScope: SearchSourceScope, unifiedSourceCount: Int) {
    when (draftSourceScope) {
        is SearchSourceScope.Single -> {
            val source = draftSourceScope.source
            SearchSourceIcon(source)
        }
        SearchSourceScope.GlobalUnified, is SearchSourceScope.Temporary -> {
            val count = when (draftSourceScope) {
                is SearchSourceScope.Temporary -> draftSourceScope.sources.size
                else -> unifiedSourceCount
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchLetterIcon("U")
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Text(
                        text = count.toString(),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceMenuOption(
    option: QueryMode,
    draftSourceScope: SearchSourceScope,
    highlightedSources: Collection<SourceKey>,
    showTitle: Boolean,
    onModeSelected: (QueryMode) -> Unit,
    onTemporarySourceToggled: (SourceKey) -> Unit,
) {
    val source = (option as? QueryMode.Source)?.source
    val selected = when {
        option == QueryMode.Unified -> draftSourceScope is SearchSourceScope.GlobalUnified
        source != null -> source in highlightedSources
        else -> false
    }
    val label = option.sourceChipLabel()
    Row(
        modifier = Modifier
            .then(if (showTitle) Modifier.fillMaxWidth() else Modifier.size(width = 64.dp, height = 48.dp))
            .heightIn(min = 48.dp)
            .clip(if (showTitle) RoundedCornerShape(0.dp) else CircleShape)
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.secondaryContainer
                    showTitle -> Color.Transparent
                    else -> MaterialTheme.colorScheme.surfaceContainer
                },
            )
            .combinedClickable(
                role = Role.Checkbox,
                onClickLabel = "Select $label",
                onLongClickLabel = source?.let { "Add or remove $label from Unified search" },
                onClick = { onModeSelected(option) },
                onLongClick = source?.let { selectedSource -> { onTemporarySourceToggled(selectedSource) } },
            )
            .semantics {
                this.selected = selected
                contentDescription = label
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showTitle) {
            Text(label)
        } else {
            Box(Modifier.clearAndSetSemantics {}) {
                if (source == null) SearchLetterIcon("U") else SearchSourceIcon(source)
            }
        }
    }
}

/** Letter marks share the white, bold treatment of Hitomi's icon. */
@Composable
private fun SearchLetterIcon(label: String) {
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
    )
}

@Composable
private fun SearchSourceIcon(source: SourceKey) {
    when {
        source == SourceKey.IWARA -> SearchLetterIcon("I")
        source.isRule34Family() -> SearchLetterIcon("R34")
        else -> SourceLogo(source, size = 18.dp)
    }
}

private fun QueryMode.sourceChipLabel(): String {
    return when (this) {
        QueryMode.Unified -> "Unified"
        is QueryMode.Source -> source.displayName()
    }
}
