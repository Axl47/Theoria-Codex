package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.post.PostTagSelection
import com.theoriacodex.app.search.searchScopeLabel
import com.theoriacodex.app.search.searchTermChipLabel
import com.theoriacodex.app.search.state.SearchDraftReducer
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.app.tags.QueryTray
import com.theoriacodex.app.tags.TagChipRow
import com.theoriacodex.app.tags.TagChipState
import com.theoriacodex.app.tags.TagPickerChip
import com.theoriacodex.app.tags.chipState
import com.theoriacodex.app.tags.compactTagCount
import com.theoriacodex.app.tags.postTagFacetOrder
import com.theoriacodex.app.tags.tagChipChunks
import com.theoriacodex.app.tags.tagTapLabel
import com.theoriacodex.app.tags.withTagTap
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey

/**
 * Builds a search from the tags in a Codex. Its query stays local until Search, which then replaces
 * the current search. Tags belong to one source's vocabulary, so changing source starts over.
 */
@Composable
internal fun CodexTagSearchSheet(
    codexName: String,
    sources: List<CodexSearchSourceOption>,
    terms: Map<SourceKey, CodexSearchTerms>,
    onDismiss: () -> Unit,
    onSearch: (SourceKey, Query) -> Unit,
) {
    var preferredSource by remember { mutableStateOf<SourceKey?>(null) }
    val source = preferredSource?.takeIf { preferred -> sources.any { it.source == preferred } }
        ?: sources.firstOrNull()?.source
    ExpandedBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Search with these tags", style = MaterialTheme.typography.titleLarge)
                Text(
                    codexName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (source == null) {
                Text(
                    "No searchable sources available",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                if (sources.size > 1) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                        CodexSourceTabs(
                            sources = sources.map(CodexSearchSourceOption::source),
                            selected = source,
                            counts = sources.associate { it.source to it.postCount },
                            noun = "post",
                            onSelect = { preferredSource = it },
                        )
                    }
                }
                CodexTagSearchEditor(source, terms[source] ?: CodexSearchTerms(emptyList(), emptyList())) { query ->
                    onSearch(source, query)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.CodexTagSearchEditor(
    source: SourceKey,
    terms: CodexSearchTerms,
    onSearch: (Query) -> Unit,
) {
    var query by remember(source) { mutableStateOf(emptySearchQuery(QueryMode.Source(source))) }
    var targetTerm by remember(source) { mutableStateOf<SearchTerm?>(null) }
    var filter by remember(source) { mutableStateOf("") }
    var drawAmount by rememberSaveable { mutableIntStateOf(DEFAULT_DRAW_AMOUNT) }
    var drawnFrom by remember(source) { mutableStateOf<String?>(null) }
    val groups = query.effectiveIncludeTermGroups
    val targetGroup = groups.indexOfFirst { targetTerm in it.terms }.takeIf { it >= 0 }
    val normalizedFilter = filter.normalizedTagFilterText()
    val focusManager = LocalFocusManager.current
    val sections = remember(terms, normalizedFilter) { codexTermSections(terms.options, normalizedFilter) }

    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutomaticTagFilterField(filter, { filter = it }, Modifier.weight(1f))
        DrawControl(drawAmount, enabled = terms.posts.isNotEmpty(), onAmount = { drawAmount = it }) {
            focusManager.clearFocus()
            drawCodexTerms(terms.posts, drawAmount)?.let { draw ->
                query = emptySearchQuery(QueryMode.Source(source)).withIncludeTermsAsRequired(draw.terms)
                targetTerm = null
                drawnFrom = draw.sourcePostId
            }
        }
    }
    drawnFrom?.let { postId ->
        Text(
            "Drawn from saved post $postId",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
        )
    }
    CodexTermList(
        sections = sections,
        emptyText = if (terms.options.isEmpty()) "No searchable tags" else "No tags match \"${filter.trim()}\"",
        modifier = Modifier.weight(1f, fill = false),
    ) { option ->
        CodexTermChip(option, source, query, targetGroup) {
            query = query.withTagTap(source, option.term, targetGroup)
            drawnFrom = null
        }
    }
    QueryTray(
        title = "This Codex search",
        emptyText = "Tap tags, or draw them from a saved post.",
        query = query,
        targetGroup = targetGroup,
        onTargetGroup = { index -> targetTerm = index?.let { groups[it].terms.first() } },
        onSearch = { onSearch(query) },
    )
}

@Composable
private fun CodexTermList(
    sections: List<Pair<String, List<CodexSearchTermOption>>>,
    emptyText: String,
    modifier: Modifier,
    chip: @Composable (CodexSearchTermOption) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        if (sections.isEmpty()) {
            item(key = "empty") {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        sections.forEach { (label, options) ->
            item(key = "section:$label") {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            val rows = options.tagChipChunks()
            items(rows.size, key = { "row:$label:$it" }) { rowIndex ->
                TagChipRow { rows[rowIndex].forEach { option -> chip(option) } }
            }
        }
    }
}

@Composable
private fun CodexTermChip(
    option: CodexSearchTermOption,
    source: SourceKey,
    query: Query,
    targetGroup: Int?,
    onTap: () -> Unit,
) {
    val term = option.term
    val state = PostTagSelection(source, query.includeTerms, query.excludeTerms).chipState(term)
    TagPickerChip(
        label = term.value,
        count = compactTagCount(option.count),
        state = state,
        enabled = targetGroup == null || state != TagChipState.NONE ||
            SearchDraftReducer.postTermForGroup(query, source, targetGroup, term) != null,
        tapLabel = tagTapLabel(searchTermChipLabel(term, excluded = false), state),
        onTap = onTap,
    )
}

@Composable
private fun DrawControl(amount: Int, enabled: Boolean, onAmount: (Int) -> Unit, onDraw: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onAmount(amount - 1) }, enabled = amount > 1) {
            Icon(Icons.Default.Remove, "Draw fewer tags")
        }
        Text(amount.toString(), style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = { onAmount(amount + 1) }, enabled = amount < MAX_DRAW_AMOUNT) {
            Icon(Icons.Default.Add, "Draw more tags")
        }
        TextButton(onClick = onDraw, enabled = enabled) { Text("Draw") }
    }
}

/** Options under their type's heading, most identifying types first and most common tags first. */
internal fun codexTermSections(
    options: List<CodexSearchTermOption>,
    normalizedFilter: String,
): List<Pair<String, List<CodexSearchTermOption>>> = options
    .filter { option -> normalizedFilter in option.term.value.normalizedTagFilterText() }
    .groupBy { option -> option.term.facet to option.term.sourceNamespace?.lowercase()?.takeUnless { it == "tag" } }
    .entries
    .sortedWith(compareBy({ postTagFacetOrder.indexOf(it.key.first) }, { it.key.second.orEmpty() }))
    .map { (type, members) -> searchScopeLabel(FacetedSearchScope(type.first, type.second)) to members }

private const val DEFAULT_DRAW_AMOUNT = 3
private const val MAX_DRAW_AMOUNT = 5
