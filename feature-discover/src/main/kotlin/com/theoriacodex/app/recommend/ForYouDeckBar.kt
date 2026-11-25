package com.theoriacodex.app.recommend

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.search.sortModeLabel
import com.theoriacodex.app.ui.components.FeedFilterSheet
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.FilterSection
import com.theoriacodex.app.ui.components.SourceSelector
import com.theoriacodex.app.ui.navigation.LocalHiddenTagsOpener
import com.theoriacodex.domain.model.SortMode

@Composable
internal fun ForYouBar(
    state: ForYouDeckUiState,
    level: ForYouLevel,
    config: ForYouScreenConfig,
    callbacks: ForYouDeckCallbacks,
    onRefresh: () -> Unit,
    onShowFilters: () -> Unit,
) {
    val grid = config.grid
    val deck by level.deck.state.collectAsStateWithLifecycle()
    var showSources by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ForYouProfileChip(state.profiles, state.activeProfileId, callbacks.onSelectProfile)
        Box(Modifier.weight(1f)) {
            SourceSelector(
                selectedSource = state.selectedSource,
                availableSources = state.availableSources,
                allSourcesLabel = "Unified",
                accessibilityLabel = "Select For You source",
                enabled = true,
                expanded = showSources,
                onExpandedChange = { showSources = it },
                onSourceSelected = { source ->
                    showSources = false
                    callbacks.onSelectSource(source)
                },
                minimumHeight = 33.dp,
                verticalPadding = 1.dp,
            )
        }
        Row {
            if (level.fork == null) {
                IconButton(onRefresh, Modifier.testTag(FOR_YOU_REFRESH_TAG)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
            if (!grid) {
                IconButton(level.deck::undo, Modifier.testTag(FOR_YOU_UNDO_TAG), enabled = deck.last != null) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
            }
            IconButton({ callbacks.onGridChange(!grid) }, Modifier.testTag(FOR_YOU_VIEW_TOGGLE_TAG)) {
                if (grid) Icon(Icons.Filled.Style, contentDescription = "Deck") else Icon(Icons.Filled.GridView, contentDescription = "Grid")
            }
            FiltersButton(config.filtered, onShowFilters)
        }
    }
}

@Composable
private fun FiltersButton(active: Boolean, onClick: () -> Unit) {
    IconButton(onClick, Modifier.testTag(FOR_YOU_FILTERS_TAG)) {
        BadgedBox(badge = { if (active) Badge(Modifier.testTag(FOR_YOU_FILTERS_ACTIVE_TAG)) }) {
            Icon(Icons.Filled.Tune, contentDescription = if (active) "Filters, active" else "Filters")
        }
    }
}

@Composable
internal fun ForYouFilterSheet(
    config: ForYouScreenConfig,
    callbacks: ForYouDeckCallbacks,
    lanes: List<ForYouLaneChoice>,
    onFocusLane: (laneId: String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hiddenTags = LocalHiddenTagsOpener.current
    FeedFilterSheet(onDismiss = onDismiss, onReset = onReset) {
        if (lanes.isNotEmpty()) {
            FilterSection("Mix") {
                lanes.forEach { lane ->
                    MixLaneChip(lane) {
                        onDismiss()
                        onFocusLane(lane.laneId)
                    }
                }
            }
        }
        FilterSection("Sort") {
            SortMode.entries.forEach { mode ->
                FilterChoiceChip(sortModeLabel(mode), config.sortMode == mode) { callbacks.onSelectSort(mode) }
            }
        }
        FilterSection("Show") {
            FilterChoiceChip("Animated only", config.animatedOnly) { callbacks.onAnimatedOnlyChange(!config.animatedOnly) }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) {
                    onDismiss()
                    hiddenTags.openHiddenTags()
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Hidden tags", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MixLaneChip(lane: ForYouLaneChoice, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(lane.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
        modifier = Modifier
            .testTag("$FOR_YOU_MIX_LANE_TAG_PREFIX${lane.laneId}")
            .alpha(if (lane.cooled) COOLED_ALPHA else 1f)
            .semantics { if (lane.cooled) stateDescription = "Cooled" },
    )
}

private const val COOLED_ALPHA = 0.45f

const val FOR_YOU_MIX_LANE_TAG_PREFIX = "for-you-mix-lane-"
const val FOR_YOU_REFRESH_TAG = "for-you-refresh"
const val FOR_YOU_UNDO_TAG = "for-you-undo"
const val FOR_YOU_VIEW_TOGGLE_TAG = "for-you-view-toggle"
const val FOR_YOU_FILTERS_TAG = "for-you-filters"
const val FOR_YOU_FILTERS_ACTIVE_TAG = "for-you-filters-active"
