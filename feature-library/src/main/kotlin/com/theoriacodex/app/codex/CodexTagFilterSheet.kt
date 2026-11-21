package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.tags.TagChipRow
import com.theoriacodex.app.tags.TagCount
import com.theoriacodex.app.tags.TagFilterField
import com.theoriacodex.app.tags.compactTagCount
import com.theoriacodex.app.tags.normalizedTagFilterText
import com.theoriacodex.app.tags.tagChipChunks
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Filters a Codex by any of its tags: typing narrows the list, and each tag chosen narrows the grid behind the sheet
 * at once to the posts carrying every chosen tag. [onSearchSites] takes the chosen tags to a site search instead.
 */
@Composable
internal fun CodexTagFilterSheet(filter: CodexTagFilter, onSearchSites: (() -> Unit)?, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val normalized = text.normalizedTagFilterText()
    val shown = remember(filter.tags, normalized) { filter.tags.filter { normalized in it.tag.normalizedTagFilterText() } }
    ExpandedBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TagFilterField(text, { text = it }, Modifier.weight(1f))
                onSearchSites?.let { search -> TextButton(onClick = search) { Text("Search sites") } }
            }
            CodexTagFilterList(shown, filter, emptyText = if (filter.tags.isEmpty()) "No tags" else "No matching tags")
        }
    }
}

@Composable
private fun CodexTagFilterList(tags: List<TagCount>, filter: CodexTagFilter, emptyText: String) {
    val rows = tags.tagChipChunks()
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        if (rows.isEmpty()) {
            item(key = "empty") {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        items(rows.size, key = { rows[it].first().key }) { index ->
            TagChipRow {
                rows[index].forEach { tag ->
                    FilterChip(
                        selected = tag.key in filter.selected,
                        onClick = { filter.toggle(tag.key) },
                        label = { Text(tag.tag) },
                        trailingIcon = {
                            Text(
                                compactTagCount(tag.count),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }
}

/** Which of Codex detail's tag sheets shows. */
internal enum class CodexTagSheet { Filter, SearchSites }

/**
 * Shows [filter]'s open sheet: [CodexTagFilterSheet], or the site search it leads to, seeded with the chosen tags,
 * whose per-source tags are built off the main thread as it opens. Without [onSearch] there is no site search. Held
 * back until the tags are known, so the sheet never claims a Codex has none while they load.
 */
@Composable
internal fun CodexTagSheets(
    filter: CodexTagFilter,
    codexName: String,
    posts: List<Post>,
    availableSources: Set<SourceKey>,
    onSearch: ((SourceKey, Query) -> Unit)?,
) {
    val onSheetChange = filter.onSheetChange
    when (filter.sheet.takeIf { filter.known }) {
        null -> Unit
        CodexTagSheet.Filter ->
            CodexTagFilterSheet(filter, onSearch?.let { { onSheetChange(CodexTagSheet.SearchSites) } }) { onSheetChange(null) }
        CodexTagSheet.SearchSites -> {
            val search = onSearch ?: return
            val options by produceState<CodexSiteSearchOptions?>(null, posts, availableSources) {
                value = withContext(Dispatchers.Default) { codexSiteSearchOptions(posts, availableSources) }
            }
            options?.let { (sources, terms) ->
                CodexTagSearchSheet(
                    codexName, sources, terms,
                    onDismiss = { onSheetChange(null) },
                    onSearch = { source, query -> onSheetChange(null); search(source, query) },
                    seedKeys = filter.selected,
                )
            }
        }
    }
}

private data class CodexSiteSearchOptions(
    val sources: List<CodexSearchSourceOption>,
    val terms: Map<SourceKey, CodexSearchTerms>,
)

private fun codexSiteSearchOptions(posts: List<Post>, availableSources: Set<SourceKey>): CodexSiteSearchOptions {
    val sources = codexSearchSourceOptions(posts, availableSources)
    return CodexSiteSearchOptions(sources, sources.associate { it.source to codexSearchTerms(posts, it.source) })
}
