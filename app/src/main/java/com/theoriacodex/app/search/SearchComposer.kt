package com.theoriacodex.app.search

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.AssistChip
import com.theoriacodex.data.repository.SavedSearchEntry
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.ui.components.TagGroupCard
import com.theoriacodex.app.ui.components.TagGroupOrLabel
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup

private data class SelectedTerm(val term: SearchTerm, val excluded: Boolean)

/**
 * Full-screen search editor: every tag stays visible, sorted under its type when the source has
 * types, and the way the next tag is added is always on screen. It edits the same draft as before,
 * so results only change when [onApply] runs.
 */
@Composable
internal fun SearchComposer(
    state: SearchUiState,
    onAction: (SearchAction) -> Unit,
    sourcePicker: @Composable () -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSourceAccounts: () -> Unit,
    onDigitsCommitted: () -> Unit,
    onDismiss: () -> Unit,
    savedSearches: List<SavedSearchEntry> = emptyList(),
    onOpenSavedSearch: (SavedSearchEntry) -> Unit = {},
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize().testTag("Search composer")) {
            SearchComposerContent(
                state, onAction, sourcePicker, onApply, onReset, onUndo,
                onOpenFavorites, onOpenSourceAccounts, onDigitsCommitted, onDismiss,
                savedSearches, onOpenSavedSearch,
            )
        }
    }
}

@Composable
private fun SearchComposerContent(
    state: SearchUiState,
    onAction: (SearchAction) -> Unit,
    sourcePicker: @Composable () -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSourceAccounts: () -> Unit,
    onDigitsCommitted: () -> Unit,
    onDismiss: () -> Unit,
    savedSearches: List<SavedSearchEntry>,
    onOpenSavedSearch: (SavedSearchEntry) -> Unit,
) {
    val input = state.suggestions.input
    val draft = state.query.draft
    val groups = draft.effectiveIncludeTermGroups
    val focus = rememberSearchInputFocus()
    val inputRequester = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(SearchAddMode.REQUIRED) }
    var targetTerm by remember { mutableStateOf<SearchTerm?>(null) }
    var awaitingNewGroup by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<SelectedTerm?>(null) }

    val targetIndex = groups.indexOfFirst { group -> targetTerm in group.terms }
    val target = groups.getOrNull(targetIndex)?.let { IndexedTermGroup(targetIndex, it) }
    val suggestions = composerSuggestions(
        mode, target, state.suggestions.facetedAutocomplete, state.suggestions.autocomplete,
    )
    val liveSelection = selection?.takeIf { selected ->
        if (selected.excluded) selected.term in draft.excludeTerms else groups.any { selected.term in it.terms }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        inputRequester.requestFocus()
    }
    // A tag added to "New group" becomes the group that the next "Any of" tag joins.
    LaunchedEffect(groups.size) {
        if (awaitingNewGroup && groups.isNotEmpty()) {
            targetTerm = groups.last().terms.first()
            awaitingNewGroup = false
        }
    }

    fun add(action: SearchAction) {
        awaitingNewGroup = mode == SearchAddMode.ANY_OF && target == null
        onAction(action)
    }
    fun commitTyped() {
        val action = composerTypedAction(mode, target, input)
        if (action == null) {
            focus.clear()
        } else if (state.suggestions.canCommitInput) {
            add(action)
            if (action is SearchAction.AddIncludeAlternative) onAction(SearchAction.ClearAutocomplete)
            if (input.trim().all(Char::isDigit)) onDigitsCommitted()
        }
    }
    fun selectMode(next: SearchAddMode) {
        mode = next
        awaitingNewGroup = false
        if (next == SearchAddMode.ANY_OF && target == null) {
            targetTerm = (groups.lastOrNull { it.terms.size > 1 } ?: groups.lastOrNull())?.terms?.first()
        }
    }
    fun addAlternativeTo(term: SearchTerm) {
        mode = SearchAddMode.ANY_OF
        targetTerm = term
        selection = null
        inputRequester.requestFocus()
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        ComposerField(
            input = input,
            validationMessage = state.query.validationMessage,
            canCommit = state.suggestions.canCommitInput,
            showAdd = input.isNotBlank(),
            inputRequester = inputRequester,
            sourcePicker = sourcePicker,
            onFocusChanged = { focused ->
                fieldFocused = focused
                onAction(if (focused) SearchAction.RefreshAutocomplete else SearchAction.ClearAutocomplete)
            },
            onValueChange = { value ->
                onAction(SearchAction.DismissValidation)
                onAction(SearchAction.AutocompleteChanged(value))
            },
            onCommit = ::commitTyped,
            onDismiss = onDismiss,
        )
        AddModeSelector(mode, ::selectMode)
        if (mode == SearchAddMode.ANY_OF) {
            AnyOfTargetRow(
                groups = groups,
                targetIndex = targetIndex,
                onSelect = { index -> targetTerm = groups.getOrNull(index)?.terms?.first() },
            )
        }
        if (state.query.supportedScopes.isNotEmpty() && (fieldFocused || input.isNotBlank())) {
            SearchScopeRow(
                scopes = state.query.supportedScopes,
                selectedScope = state.query.selectedScope,
                onScopeSelected = { scope -> onAction(SearchAction.SelectSuggestionScope(scope)) },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (suggestions.isNotEmpty()) {
                ComposerSuggestionList(suggestions) { suggestion ->
                    add(suggestion.add)
                    onAction(SearchAction.ClearAutocomplete)
                }
            } else {
                ComposerTagList(
                    state = state,
                    savedSearches = savedSearches,
                    onOpenSavedSearch = onOpenSavedSearch,
                    selection = liveSelection,
                    onSelect = { selected -> selection = selected.takeUnless { it == liveSelection } },
                    onAddAlternative = ::addAlternativeTo,
                    onOpenSourceAccounts = onOpenSourceAccounts,
                    onAction = onAction,
                )
            }
        }
        liveSelection?.let { selected ->
            SelectedTermActions(
                selected = selected,
                groupIndex = groups.indexOfFirst { selected.term in it.terms },
                groupSize = groups.firstOrNull { selected.term in it.terms }?.terms?.size ?: 0,
                onAddAlternative = { addAlternativeTo(selected.term) },
                onAction = { action ->
                    onAction(action)
                    selection = null
                },
            )
        }
        HorizontalDivider()
        ComposerFooter(state.canUndoSearch, state.hasPendingChanges, onUndo, onReset, onOpenFavorites, onApply)
    }
}

@Composable
private fun ComposerField(
    input: String,
    validationMessage: String?,
    canCommit: Boolean,
    showAdd: Boolean,
    inputRequester: FocusRequester,
    sourcePicker: @Composable () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close search editor") }
        OutlinedTextField(
            modifier = Modifier
                .weight(1f)
                .testTag("Search query input")
                .focusRequester(inputRequester)
                .onFocusChanged { onFocusChanged(it.isFocused) },
            value = input,
            onValueChange = onValueChange,
            shape = RoundedCornerShape(28.dp),
            singleLine = true,
            isError = validationMessage != null,
            supportingText = validationMessage?.let { message -> { Text(message) } },
            placeholder = { Text("Add a tag", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = KeyboardType.Text,
                capitalization = KeyboardCapitalization.None,
            ),
            keyboardActions = KeyboardActions(onDone = { onCommit() }),
            trailingIcon = if (showAdd) {
                { TextButton(onClick = onCommit, enabled = canCommit) { Text("Add") } }
            } else {
                null
            },
        )
        sourcePicker()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddModeSelector(mode: SearchAddMode, onSelect: (SearchAddMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        SearchAddMode.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = mode == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, SearchAddMode.entries.size),
                label = { Text(option.label, maxLines = 1) },
            )
        }
    }
}

/** Shows which group an "Any of" tag joins; "New group" starts one from the next tag. */
@Composable
private fun AnyOfTargetRow(
    groups: List<SearchTermGroup>,
    targetIndex: Int,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(groups.size) { index ->
            FilterChip(
                selected = index == targetIndex,
                onClick = { onSelect(index) },
                label = {
                    Text(
                        groups[index].terms.joinToString(" or ") { it.value },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
        item {
            FilterChip(
                selected = targetIndex < 0,
                onClick = { onSelect(-1) },
                label = { Text("New group") },
                leadingIcon = { Icon(Icons.Default.Add, null) },
            )
        }
    }
}

@Composable
private fun SearchScopeRow(
    scopes: List<FacetedSearchScope>,
    selectedScope: FacetedSearchScope,
    onScopeSelected: (FacetedSearchScope) -> Unit,
) {
    var moreExpanded by remember { mutableStateOf(false) }
    val allScope = scopes.firstOrNull(FacetedSearchScope::isAll)
    val primaryScopes = primarySearchFacets.mapNotNull { facet -> scopes.firstOrNull { it.facet == facet } }
    val secondaryScopes = secondarySearchFacets.mapNotNull { facet -> scopes.firstOrNull { it.facet == facet } }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        allScope?.let { scope ->
            item {
                FilterChip(
                    selected = selectedScope.isAll,
                    onClick = { onScopeSelected(scope) },
                    label = { Text(searchScopeLabel(scope)) },
                )
            }
        }
        items(primaryScopes) { scope ->
            FilterChip(
                selected = selectedScope.facet == scope.facet &&
                    (selectedScope == scope || scope.facet == SearchFacet.TAG),
                onClick = { onScopeSelected(scope) },
                label = { Text(searchScopeLabel(scope)) },
            )
        }
        if (secondaryScopes.isNotEmpty()) {
            item {
                Box {
                    val selectedSecondary = secondaryScopes.firstOrNull { it.facet == selectedScope.facet }
                    FilterChip(
                        selected = selectedSecondary != null,
                        onClick = { moreExpanded = true },
                        label = { Text(selectedSecondary?.let(::searchScopeLabel) ?: "More") },
                    )
                    DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                        secondaryScopes.forEach { scope ->
                            DropdownMenuItem(
                                text = { Text(searchScopeLabel(scope)) },
                                onClick = {
                                    onScopeSelected(scope)
                                    moreExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerSuggestionList(
    suggestions: List<ComposerSuggestion>,
    onAdd: (ComposerSuggestion) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(suggestions) { suggestion ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAdd(suggestion) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(suggestion.text, style = MaterialTheme.typography.bodyLarge)
                    if (suggestion.meta.isNotBlank()) {
                        Text(
                            suggestion.meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(Icons.Default.Add, "Add ${suggestion.text}", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerTagList(
    state: SearchUiState,
    savedSearches: List<SavedSearchEntry>,
    onOpenSavedSearch: (SavedSearchEntry) -> Unit,
    selection: SelectedTerm?,
    onSelect: (SelectedTerm) -> Unit,
    onAddAlternative: (SearchTerm) -> Unit,
    onOpenSourceAccounts: () -> Unit,
    onAction: (SearchAction) -> Unit,
) {
    val draft = state.query.draft
    // Types are only comparable within one source that declares them; Unified stays flat.
    val typedSource = draft.mode is QueryMode.Source && state.query.supportedScopes.isNotEmpty()
    val sections = composerSections(draft.effectiveIncludeTermGroups, typedSource)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (savedSearches.isNotEmpty()) {
            ComposerSectionTitle("Saved")
            SavedSearchChips(savedSearches, onOpenSavedSearch)
        }
        sections.forEach { section ->
            ComposerSectionTitle(section.title)
            ComposerSectionTerms(section, typedSource, selection, onSelect, onAddAlternative)
        }
        if (draft.excludeTerms.isNotEmpty()) {
            ComposerSectionTitle("Excluded")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                draft.excludeTerms.forEach { term ->
                    ComposerTermChip(
                        label = searchTermChipLabel(term, excluded = true),
                        excluded = true,
                        selected = selection == SelectedTerm(term, true),
                        onClick = { onSelect(SelectedTerm(term, true)) },
                    )
                }
            }
        }
        if (sections.isEmpty() && draft.excludeTerms.isEmpty()) {
            Text(
                "Add tags to build your search.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (
            state.query.appliedSourceScope !is SearchSourceScope.Single &&
            visibleSourceStatusChipStatuses(state.content.statuses).isNotEmpty()
        ) {
            SourceStatusActions(state, onAction, onOpenSourceAccounts)
        }
    }
}

/** One tap reruns a saved search, replacing the current draft. */
@Composable
private fun SavedSearchChips(searches: List<SavedSearchEntry>, onOpen: (SavedSearchEntry) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(searches, key = SavedSearchEntry::id) { entry ->
            AssistChip(
                onClick = { onOpen(entry) },
                label = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Bookmark, null, Modifier.size(16.dp)) },
            )
        }
    }
}

@Composable
private fun ComposerSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerSectionTerms(
    section: ComposerSection,
    typedSource: Boolean,
    selection: SelectedTerm?,
    onSelect: (SelectedTerm) -> Unit,
    onAddAlternative: (SearchTerm) -> Unit,
) {
    // Under a type heading the type is already stated, so chips only name the tag.
    fun label(term: SearchTerm) = if (typedSource) term.value else searchTermChipLabel(term, excluded = false)
    @Composable
    fun chip(term: SearchTerm) = ComposerTermChip(
        label = label(term),
        excluded = false,
        selected = selection == SelectedTerm(term, false),
        onClick = { onSelect(SelectedTerm(term, false)) },
    )

    val (single, alternatives) = section.groups.partition { (_, group) -> group.terms.size == 1 }
    if (single.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            single.forEach { (_, group) -> chip(group.terms.single()) }
        }
    }
    alternatives.forEach { (_, group) ->
        TagGroupCard(active = false) {
            group.terms.forEachIndexed { index, term ->
                if (index > 0) TagGroupOrLabel()
                chip(term)
            }
            TextButton(onClick = { onAddAlternative(group.terms.first()) }) { Text("+ or") }
        }
    }
}

@Composable
private fun ComposerTermChip(label: String, excluded: Boolean, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    InputChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = if (excluded) {
            InputChipDefaults.inputChipColors(
                labelColor = colors.error,
                selectedLabelColor = colors.error,
                selectedContainerColor = colors.error.copy(alpha = 0.14f),
            )
        } else {
            InputChipDefaults.inputChipColors()
        },
    )
}

/** Every tag behaves the same: tap to select, then choose what happens to it here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedTermActions(
    selected: SelectedTerm,
    groupIndex: Int,
    groupSize: Int,
    onAddAlternative: () -> Unit,
    onAction: (SearchAction) -> Unit,
) {
    val term = selected.term
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        if (selected.excluded) {
            TextButton(onClick = {
                onAction(SearchAction.RemoveExcludeTerm(term))
                onAction(SearchAction.AddIncludeTerm(term))
            }) { Text("Must have") }
            TextButton(onClick = { onAction(SearchAction.RemoveExcludeTerm(term)) }) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
            return@FlowRow
        }
        TextButton(onClick = onAddAlternative) { Text("Add “or”") }
        if (groupSize > 1) {
            TextButton(onClick = { onAction(SearchAction.SplitIncludeAlternative(groupIndex, term)) }) {
                Text("Make required")
            }
        }
        TextButton(onClick = {
            onAction(SearchAction.RemoveIncludeTerm(term))
            onAction(SearchAction.AddExcludeTerm(term))
        }) { Text("Exclude") }
        TextButton(onClick = { onAction(SearchAction.RemoveIncludeTerm(term)) }) {
            Text("Remove", color = MaterialTheme.colorScheme.error)
        }
        if (groupSize > 1) {
            TextButton(onClick = { onAction(SearchAction.RemoveIncludeGroup(groupIndex)) }) {
                Text("Remove group", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ComposerFooter(
    canUndo: Boolean,
    canReset: Boolean,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onOpenFavorites: () -> Unit,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(enabled = canUndo, onClick = onUndo) { Text("Undo") }
        TextButton(enabled = canReset, onClick = onReset) { Text("Reset") }
        TextButton(onClick = onOpenFavorites) { Text("Favorites") }
        Box(Modifier.weight(1f))
        Button(onClick = onApply, modifier = Modifier.testTag("Search composer apply")) { Text("Search") }
    }
}
