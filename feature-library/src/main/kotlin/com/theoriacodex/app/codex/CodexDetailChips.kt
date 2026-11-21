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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.SingleChoiceMenu
import com.theoriacodex.app.ui.navigation.TriageMode
import com.theoriacodex.app.ui.navigation.TriageRequest
import com.theoriacodex.app.ui.navigation.rememberTriageAction
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

/**
 * The match key of the tag filtering Codex [codexId], cleared when [tags] no longer offer it. Transient: a quick look
 * inside this Codex, so it is neither a saved filter nor part of the restore record.
 */
@Composable
internal fun rememberCodexTagFilter(codexId: String, tags: List<CodexTagCount>): MutableState<String?> {
    val key = rememberSaveable(codexId) { mutableStateOf<String?>(null) }
    LaunchedEffect(tags) { if (tags.none { it.key == key.value }) key.value = null }
    return key
}

/**
 * The Triage chip's action for Codex [codexId], where a left swipe removes the post from it. Likes keeps the plain
 * deck, where left skips: removing there would unlike, which reads wrong beside right meaning like. Null without
 * posts, and for the virtual Codices ([real] false), which the deck cannot open.
 */
@Composable
internal fun rememberCodexTriageAction(codexId: String, real: Boolean, hasPosts: Boolean): (() -> Unit)? {
    val mode = if (codexId.startsWith(LIKES_CODEX_ID_PREFIX)) TriageMode.Browse else TriageMode.RemoveFromCodex()
    val request = TriageRequest.codex(codexId, mode)
    return rememberTriageAction(request.source, request.feedKey.takeIf { real }, hasPosts, request.mode)
}

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
