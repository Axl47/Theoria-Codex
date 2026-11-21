package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.SingleChoiceMenu
import com.theoriacodex.data.repository.CodexSortMode

/** Codex detail's sort, the sorts it offers, and the transient tag filter, for [CodexDetailChipRow]. */
internal class CodexDetailChips(
    val sortMode: CodexSortMode,
    val sortModes: List<CodexSortMode>,
    val onSortChange: (CodexSortMode) -> Unit,
    val tags: List<CodexTagCount>,
    val selectedTagKey: String?,
    val onSelectTag: (String?) -> Unit,
    val onTriage: (() -> Unit)?,
)

/** The sorts a collection offers: length sorts mean nothing without animations, unless one is already chosen. */
internal fun codexSortModes(hasAnimatedPosts: Boolean, current: CodexSortMode): List<CodexSortMode> =
    CodexSortMode.entries.filter { hasAnimatedPosts || !it.usesDuration || it == current }

/**
 * The row under Codex detail's header: the sort as a chip with a menu, Triage, then the Codex's most common tags.
 * A tag filters the grid at once; tapping it again clears it.
 */
@Composable
internal fun CodexDetailChipRow(chips: CodexDetailChips, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag("codex-chip-row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "sort") { CodexSortChip(chips.sortMode, chips.sortModes, chips.onSortChange) }
        chips.onTriage?.let { triage ->
            item(key = "triage") {
                AssistChip(
                    onClick = triage,
                    label = { Text("Triage") },
                    leadingIcon = { Icon(Icons.Default.Style, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
        }
        items(chips.tags, key = { "tag:${it.key}" }) { tag ->
            val selected = tag.key == chips.selectedTagKey
            FilterChip(
                selected = selected,
                onClick = { chips.onSelectTag(tag.key.takeUnless { selected }) },
                label = { Text(tag.tag) },
            )
        }
    }
}

@Composable
private fun CodexSortChip(sortMode: CodexSortMode, sortModes: List<CodexSortMode>, onSortChange: (CodexSortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(sortMode.label) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort", modifier = Modifier.size(18.dp)) },
        )
        SingleChoiceMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            options = sortModes,
            selected = sortMode,
            label = { it.label },
            onSelect = onSortChange,
        )
    }
}
