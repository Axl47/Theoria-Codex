package com.theoriacodex.app.recommend

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** "Mix" and one chip per interest; a chip narrows the Mix to its lane. A single interest has nothing to narrow, so it draws no chips. */
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

/** The trail so far, "For You › tag › Similar": a crumb returns to its level, and a tag trail offers Save search. */
@Composable
internal fun ForYouCrumbs(levels: List<ForYouLevel>, onCrumb: (depth: Int) -> Unit, onSaveSearch: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f).testTag(FOR_YOU_CRUMBS_TAG), verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(levels, key = { _, level -> level.id }) { depth, level ->
                Crumb(level.label, depth, current = depth == levels.lastIndex) { onCrumb(depth) }
            }
        }
        if (onSaveSearch != null) TextButton(onSaveSearch, Modifier.testTag(FOR_YOU_SAVE_SEARCH_TAG)) { Text("Save search") }
    }
}

@Composable
private fun Crumb(label: String, depth: Int, current: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (depth > 0) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 160.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(if (current) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = "Back to $label", onClick = onClick))
                .padding(horizontal = 4.dp, vertical = 12.dp)
                .testTag("$FOR_YOU_CRUMB_TAG_PREFIX$depth"),
        )
    }
}

const val FOR_YOU_CHIPS_TAG = "for-you-chips"
const val FOR_YOU_CRUMBS_TAG = "for-you-crumbs"
const val FOR_YOU_CRUMB_TAG_PREFIX = "for-you-crumb-"
const val FOR_YOU_SAVE_SEARCH_TAG = "for-you-save-search"
