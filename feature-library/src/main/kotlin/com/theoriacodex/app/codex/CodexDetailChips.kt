package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.tags.TagCount
import com.theoriacodex.app.ui.navigation.TriageMode
import com.theoriacodex.app.ui.navigation.TriageRequest
import com.theoriacodex.app.ui.navigation.TriageSeed
import com.theoriacodex.app.ui.navigation.rememberTriageAction
import com.theoriacodex.app.ui.components.SortDirectionMark
import com.theoriacodex.app.ui.components.SortMenu
import com.theoriacodex.app.ui.components.SortOption
import com.theoriacodex.app.ui.components.label
import com.theoriacodex.app.ui.components.labelOf
import com.theoriacodex.data.repository.CodexPostSortKey
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.domain.model.next
import com.theoriacodex.domain.model.Post

internal class CodexDetailChips(
    val sortMode: CodexSortMode,
    val sortOptions: List<SortOption<CodexPostSortKey>>,
    val onSortChange: (CodexSortMode) -> Unit,
    val tagFilter: CodexTagFilter,
    val onTriage: (() -> Unit)?,
)

internal class CodexTagFilter(
    val tags: List<TagCount>,
    val selected: List<String>,
    val onSelectedChange: (List<String>) -> Unit,
    val sheet: CodexTagSheet?,
    val onSheetChange: (CodexTagSheet?) -> Unit,
    val known: Boolean = true,
) {
    val selectedTags: List<String> get() = selected.mapNotNull { key -> tags.firstOrNull { it.key == key }?.tag }

    fun toggle(key: String) = onSelectedChange(if (key in selected) selected - key else selected + key)
}

@Composable
internal fun rememberCodexTagFilter(
    codexId: String,
    posts: List<Post>,
    settled: Boolean,
    sheetRequested: Boolean,
    onSheetRequestHandled: () -> Unit,
): CodexTagFilter {
    val tags by rememberCodexTags(posts)
    var keys by rememberSaveable(codexId, saver = TagKeysSaver) { mutableStateOf(emptyList()) }
    var sheet by rememberSaveable { mutableStateOf<CodexTagSheet?>(null) }
    LaunchedEffect(tags, settled) {
        tags?.takeIf { settled }?.let { known -> keys = keys.filter { key -> known.any { it.key == key } } }
    }
    LaunchedEffect(sheetRequested) {
        if (sheetRequested) {
            sheet = CodexTagSheet.Filter
            onSheetRequestHandled()
        }
    }
    return CodexTagFilter(tags.orEmpty(), keys, { keys = it }, sheet, { sheet = it }, known = tags != null)
}

private val TagKeysSaver = Saver<MutableState<List<String>>, ArrayList<String>>(
    save = { ArrayList(it.value) },
    restore = { mutableStateOf(it) },
)

@Composable
internal fun rememberCodexTriageAction(codexId: String, real: Boolean, seed: TriageSeed): (() -> Unit)? {
    val mode = if (codexId.startsWith(LIKES_CODEX_ID_PREFIX)) TriageMode.Browse else TriageMode.RemoveFromCodex()
    val request = TriageRequest.codex(codexId, mode)
    return rememberTriageAction(request.source, request.feedKey.takeIf { real }, seed, request.mode)
}

internal fun codexSortOptions(hasAnimatedPosts: Boolean, current: CodexSortMode): List<SortOption<CodexPostSortKey>> =
    CodexPostSortOptions.filter { hasAnimatedPosts || it.key != CodexPostSortKey.LENGTH || it.key == current.choice.key }

@Composable
internal fun CodexDetailChipRow(chips: CodexDetailChips, modifier: Modifier = Modifier) {
    val filter = chips.tagFilter
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag("codex-chip-row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "tags") {
            CodexTagFilterChip(filter.selectedTags, { filter.onSheetChange(CodexTagSheet.Filter) }) { filter.onSelectedChange(emptyList()) }
        }
        item(key = "sort") { CodexSortChip(chips.sortMode, chips.sortOptions, chips.onSortChange) }
        chips.onTriage?.let { triage ->
            item(key = "triage") {
                AssistChip(
                    onClick = triage,
                    label = { Text("Swipe") },
                    leadingIcon = { Icon(Icons.Default.Style, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
        }
        items(filter.tags.take(CODEX_TAG_CHIP_LIMIT), key = { "tag:${it.key}" }) { tag ->
            FilterChip(
                selected = tag.key in filter.selected,
                onClick = { filter.toggle(tag.key) },
                label = { Text(tag.tag) },
            )
        }
    }
}

@Composable
private fun CodexTagFilterChip(selectedTags: List<String>, onOpen: () -> Unit, onClear: () -> Unit) {
    val active = selectedTags.isNotEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = active,
            onClick = onOpen,
            label = {
                Text(
                    if (active) selectedTags.joinToString(", ") else "Tags",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
            },
            leadingIcon = { Icon(Icons.Default.Sell, contentDescription = null, Modifier.size(18.dp)) },
        )
        if (active) {
            IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "Clear tags", Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun CodexSortChip(sortMode: CodexSortMode, options: List<SortOption<CodexPostSortKey>>, onSortChange: (CodexSortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val direction = sortMode.choice.direction
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(CodexPostSortOptions.labelOf(sortMode.choice.key)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort", modifier = Modifier.size(18.dp)) },
            trailingIcon = direction?.let { { SortDirectionMark(it) } },
            modifier = Modifier.semantics { direction?.let { stateDescription = it.label } },
        )
        SortMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            options = options,
            choice = sortMode.choice,
            onPick = { key -> onSortChange(CodexSortMode.entries.next(sortMode, key)) },
        )
    }
}
