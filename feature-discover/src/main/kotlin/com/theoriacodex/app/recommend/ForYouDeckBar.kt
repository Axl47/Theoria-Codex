package com.theoriacodex.app.recommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.search.sortModeLabel
import com.theoriacodex.app.ui.components.FeedFilterSheet
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.FilterSection
import com.theoriacodex.app.ui.components.SourceSelector
import com.theoriacodex.app.ui.navigation.LocalHiddenTagsOpener
import com.theoriacodex.domain.model.SortMode

/** For You's bar: the profile, the source, Undo for the deck's last swipe, the deck and grid toggle, and filters. */
@Composable
internal fun ForYouBar(
    state: ForYouDeckUiState,
    level: ForYouLevel,
    grid: Boolean,
    callbacks: ForYouDeckCallbacks,
    onShowFilters: () -> Unit,
) {
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
            if (!grid) {
                IconButton(level.deck::undo, Modifier.testTag(FOR_YOU_UNDO_TAG), enabled = deck.last != null) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
            }
            IconButton({ callbacks.onGridChange(!grid) }, Modifier.testTag(FOR_YOU_VIEW_TOGGLE_TAG)) {
                if (grid) Icon(Icons.Filled.Style, contentDescription = "Deck") else Icon(Icons.Filled.GridView, contentDescription = "Grid")
            }
            IconButton(onShowFilters, Modifier.testTag(FOR_YOU_FILTERS_TAG)) { Icon(Icons.Filled.Tune, contentDescription = "Filters") }
        }
    }
}

/** Sort and the media filter, which start a fresh Mix, and the way to the profile's hidden tags. */
@Composable
internal fun ForYouFilterSheet(config: ForYouScreenConfig, callbacks: ForYouDeckCallbacks, onDismiss: () -> Unit) {
    val hiddenTags = LocalHiddenTagsOpener.current
    val reset = {
        callbacks.onSelectSort(SortMode.NEWEST)
        callbacks.onAnimatedOnlyChange(false)
    }
    FeedFilterSheet(onDismiss = onDismiss, onReset = reset) {
        FilterSection("Sort") {
            SortMode.entries.forEach { mode ->
                FilterChoiceChip(sortModeLabel(mode), config.sortMode == mode) { callbacks.onSelectSort(mode) }
            }
        }
        FilterSection("Show") {
            FilterChoiceChip("Animated only", config.animatedOnly) { callbacks.onAnimatedOnlyChange(!config.animatedOnly) }
            FilterChoiceChip("Hidden tags", selected = false) {
                onDismiss()
                hiddenTags.openHiddenTags()
            }
        }
    }
}

const val FOR_YOU_UNDO_TAG = "for-you-undo"
const val FOR_YOU_VIEW_TOGGLE_TAG = "for-you-view-toggle"
const val FOR_YOU_FILTERS_TAG = "for-you-filters"
