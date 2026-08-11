package com.theoriacodex.app.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.AutocompleteListShell
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup

@Composable
internal fun TagRow(
    includeGroups: List<SearchTermGroup>,
    excludeTerms: List<SearchTerm>,
    onEditIncludeGroup: (Int) -> Unit,
    onRemoveExclude: (SearchTerm) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        includeGroups.forEachIndexed { index, group ->
            item(key = "include-group:$index:${group.terms.joinToString { it.value }}") {
                val label = if (group.terms.size == 1) {
                    searchTermChipLabel(group.terms.single(), excluded = false)
                } else {
                    group.terms.joinToString(prefix = "(", postfix = ")", separator = " OR ") { term ->
                        searchTermChipLabel(term, excluded = false)
                    }
                }
                AssistChip(
                    onClick = { onEditIncludeGroup(index) },
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        items(excludeTerms.size) { index ->
            val term = excludeTerms[index]
            AssistChip(
                onClick = { onRemoveExclude(term) },
                label = { Text(searchTermChipLabel(term, excluded = true)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IncludeTagGroupSheet(
    groupIndex: Int,
    group: SearchTermGroup,
    autocompleteInput: String,
    facetedSuggestions: List<FacetedTagSuggestion>,
    suggestions: List<TagSuggestion>,
    onAutocompleteChanged: (String) -> Unit,
    onAddAlternative: (SearchTerm) -> Unit,
    onRemoveTerm: (SearchTerm) -> Unit,
    onRequireTerm: (SearchTerm) -> Unit,
    onRemoveGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    val anchor = group.terms.first()
    val addTypedAlternative = {
        autocompleteInput.trim().takeIf(String::isNotBlank)?.let { value ->
            onAddAlternative(anchor.copy(value = value))
        }
        Unit
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        IncludeTagGroupSheetContent(
            groupIndex = groupIndex,
            group = group,
            anchor = anchor,
            autocompleteInput = autocompleteInput,
            facetedSuggestions = facetedSuggestions,
            suggestions = suggestions,
            onAutocompleteChanged = onAutocompleteChanged,
            onAddAlternative = onAddAlternative,
            onRemoveTerm = onRemoveTerm,
            onRequireTerm = onRequireTerm,
            onRemoveGroup = onRemoveGroup,
            addTypedAlternative = addTypedAlternative,
        )
    }
}

@Composable
private fun IncludeTagGroupSheetContent(
    groupIndex: Int,
    group: SearchTermGroup,
    anchor: SearchTerm,
    autocompleteInput: String,
    facetedSuggestions: List<FacetedTagSuggestion>,
    suggestions: List<TagSuggestion>,
    onAutocompleteChanged: (String) -> Unit,
    onAddAlternative: (SearchTerm) -> Unit,
    onRemoveTerm: (SearchTerm) -> Unit,
    onRequireTerm: (SearchTerm) -> Unit,
    onRemoveGroup: () -> Unit,
    addTypedAlternative: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Required tag group ${groupIndex + 1}", style = MaterialTheme.typography.titleMedium)
        Text(
            "A post must match at least one tag in this group. Separate groups are all required.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IncludeTagGroupTerms(group, onRequireTerm, onRemoveTerm)
        HorizontalDivider()
        IncludeTagAlternativeEditor(
            anchor,
            autocompleteInput,
            facetedSuggestions,
            suggestions,
            onAutocompleteChanged,
            onAddAlternative,
            addTypedAlternative,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onRemoveGroup) { Text("Remove group") }
            TextButton(onClick = addTypedAlternative, enabled = autocompleteInput.isNotBlank()) {
                Text("Add alternative")
            }
        }
    }
}

@Composable
private fun IncludeTagGroupTerms(
    group: SearchTermGroup,
    onRequireTerm: (SearchTerm) -> Unit,
    onRemoveTerm: (SearchTerm) -> Unit,
) {
    group.terms.forEach { term ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(term.value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (group.terms.size > 1) {
                TextButton(onClick = { onRequireTerm(term) }) { Text("Require") }
            }
            TextButton(onClick = { onRemoveTerm(term) }) { Text("Remove") }
        }
    }
}

@Composable
private fun IncludeTagAlternativeEditor(
    anchor: SearchTerm,
    input: String,
    facetedSuggestions: List<FacetedTagSuggestion>,
    suggestions: List<TagSuggestion>,
    onInputChanged: (String) -> Unit,
    onAddAlternative: (SearchTerm) -> Unit,
    onDone: () -> Unit,
) {
    Text("Add an OR alternative", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = input,
        onValueChange = onInputChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Tag") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
    val options = alternativeSuggestions(anchor, facetedSuggestions, suggestions)
    if (options.isNotEmpty()) {
        AlternativeAutocompletePanel(options, onAddAlternative)
    }
}

private fun alternativeSuggestions(
    anchor: SearchTerm,
    facetedSuggestions: List<FacetedTagSuggestion>,
    suggestions: List<TagSuggestion>,
): List<AlternativeTagSuggestion> {
    if (facetedSuggestions.isNotEmpty()) {
        return facetedSuggestions.map { suggestion ->
            AlternativeTagSuggestion(suggestion.toSearchTerm(), facetedSuggestionMetaLabel(suggestion))
        }
    }
    if (anchor.facet != SearchFacet.TAG || anchor.sourceNamespace != null) return emptyList()
    return suggestions.map { suggestion ->
        AlternativeTagSuggestion(
            term = anchor.copy(value = suggestion.text),
            meta = listOfNotNull(suggestion.type, suggestion.count?.toString()).joinToString(" • "),
        )
    }
}

private data class AlternativeTagSuggestion(
    val term: SearchTerm,
    val meta: String,
)

@Composable
private fun AlternativeAutocompletePanel(
    suggestions: List<AlternativeTagSuggestion>,
    onAddAlternative: (SearchTerm) -> Unit,
) {
    AutocompleteListShell(items = suggestions) { suggestion ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAddAlternative(suggestion.term) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(suggestion.term.value, style = MaterialTheme.typography.bodyMedium)
                suggestion.meta.takeIf(String::isNotBlank)?.let { meta ->
                    Text(meta, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Add", color = MaterialTheme.colorScheme.primary)
        }
    }
}
