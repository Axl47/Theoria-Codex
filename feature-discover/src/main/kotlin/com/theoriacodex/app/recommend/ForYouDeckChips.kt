package com.theoriacodex.app.recommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.TrailCrumb
import com.theoriacodex.app.ui.components.TrailCrumbArrow

@Composable
internal fun ForYouChips(chips: List<ForYouChip>, only: String?, onSelect: (laneId: String?) -> Unit) {
    if (chips.size < 2) return
    LazyRow(
        Modifier.fillMaxWidth().testTag(FOR_YOU_CHIPS_TAG),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "mix") { InterestChip("Mix", selected = only == null) { onSelect(null) } }
        itemsIndexed(chips, key = { _, chip -> chip.laneId }) { _, chip ->
            InterestChip(chip.label, selected = only == chip.laneId) { onSelect(chip.laneId) }
        }
    }
}

@Composable
private fun InterestChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
    )
}

@Composable
internal fun ForYouCrumbs(levels: List<ForYouLevel>, onCrumb: (depth: Int) -> Unit, saved: Boolean, onSaveSearch: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f).testTag(FOR_YOU_CRUMBS_TAG), verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(levels, key = { _, level -> level.id }) { depth, level ->
                TrailCrumb(
                    level.label,
                    current = depth == levels.lastIndex,
                    color = MaterialTheme.colorScheme.onSurface,
                    dimColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    arrow = if (depth > 0) TrailCrumbArrow.Before else TrailCrumbArrow.None,
                    maxWidth = 160.dp,
                    verticalPadding = 12.dp,
                    testTag = "$FOR_YOU_CRUMB_TAG_PREFIX$depth",
                ) { onCrumb(depth) }
            }
        }
        if (onSaveSearch != null) {
            IconButton(onSaveSearch, Modifier.testTag(FOR_YOU_SAVE_SEARCH_TAG), enabled = !saved) {
                val tint = MaterialTheme.colorScheme.primary
                if (saved) Icon(Icons.Filled.Bookmark, "Saved", tint = tint) else Icon(Icons.Filled.BookmarkBorder, "Save search", tint = tint)
            }
        }
    }
}

const val FOR_YOU_CHIPS_TAG = "for-you-chips"
const val FOR_YOU_CRUMBS_TAG = "for-you-crumbs"
const val FOR_YOU_CRUMB_TAG_PREFIX = "for-you-crumb-"
const val FOR_YOU_SAVE_SEARCH_TAG = "for-you-save-search"
