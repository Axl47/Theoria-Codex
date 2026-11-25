package com.theoriacodex.app.search

import com.theoriacodex.app.search.omnibox.OmniboxEntry
import com.theoriacodex.app.search.omnibox.matchOmnibox
import com.theoriacodex.app.search.omnibox.placeOmniboxMatches
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.theoriacodex.app.recents.SavedSearchName
import com.theoriacodex.app.recents.SavedSearchNameDialog
import androidx.compose.material.icons.filled.BookmarkAdd
import com.theoriacodex.app.ui.components.NameEntryDialog
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.tags.TagPickerChip
import com.theoriacodex.app.tags.tagTapLabel
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.data.repository.FavoriteTagEntry
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm

private val COMPACT_QUERY_MAX_HEIGHT = 144.dp

private const val COMPACT_QUERY_MAX_FRACTION = 0.4f

private val SAVED_CHIP_MAX_WIDTH = 240.dp

@Composable
internal fun SearchComposer(
    state: SearchUiState,
    onAction: (SearchAction) -> Unit,
    sourcePicker: @Composable () -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onManageSavedTags: () -> Unit,
    onOpenSourceAccounts: () -> Unit,
    onDismiss: () -> Unit,
    savedSearches: ComposerSavedSearches = ComposerSavedSearches(),
    savedTags: ComposerSavedTags = ComposerSavedTags(emptyList()),
    omnibox: List<OmniboxEntry> = emptyList(),
    onOpenFilters: () -> Unit = {},
) {
    SearchComposerSheet(replacing = state.replacingResults, onDismiss = onDismiss) {
        SearchComposerContent(
            state, onAction, sourcePicker, onApply, onReset, onUndo,
            onManageSavedTags, onOpenSourceAccounts, onDismiss,
            savedSearches, savedTags, omnibox, onOpenFilters,
        )
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
    onManageSavedTags: () -> Unit,
    onOpenSourceAccounts: () -> Unit,
    onDismiss: () -> Unit,
    savedSearches: ComposerSavedSearches,
    savedTags: ComposerSavedTags,
    omnibox: List<OmniboxEntry>,
    onOpenFilters: () -> Unit,
) {
    val input = state.suggestions.input
    val draft = state.query.draft
    val terms = ComposerTerms(draft.effectiveIncludeTermGroups, draft.excludeTerms, state.query.draftSourceScope)
    val focus = rememberSearchInputFocus()
    val inputRequester = remember { FocusRequester() }
    val snackbars = remember { SnackbarHostState() }
    var excludeNext by rememberSaveable { mutableStateOf(false) }
    var targetTerm by remember { mutableStateOf<SearchTerm?>(null) }
    var selection by remember { mutableStateOf<SelectedTerm?>(null) }
    var removalUndo by remember { mutableStateOf<ComposerRemovalUndo?>(null) }

    val targetIndex = terms.groups.indexOfFirst { group -> targetTerm in group.terms }
    val target = when {
        targetIndex >= 0 -> ComposerAddTarget.Alternative(targetIndex, terms.groups[targetIndex])
        excludeNext -> ComposerAddTarget.Excluded
        else -> ComposerAddTarget.Required
    }
    val omniboxMatches = remember(omnibox, input) { matchOmnibox(omnibox, input) }
    val suggestions = composerSuggestions(target, state, savedTags.entries)
        .copy(omnibox = omniboxMatches.takeUnless { target is ComposerAddTarget.Alternative }.orEmpty())
    val liveSelection = selection?.takeIf { selected ->
        if (selected.excluded) selected.term in terms.excluded else terms.groups.any { selected.term in it.terms }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        inputRequester.requestFocus()
    }
    ComposerRemovalUndoEffect(removalUndo, terms, snackbars, onAction) { removalUndo = null }

    fun commitTyped() = commitComposerInput(
        target, input, state.suggestions.canCommitInput, focus::clear, onAction,
    )
    fun submitTyped() = if (composerTypedAction(target, input) == null) onApply() else commitTyped()
    fun addAlternativeTo(term: SearchTerm) {
        excludeNext = false
        targetTerm = term
        selection = null
        inputRequester.requestFocus()
    }
    val queryActions = ComposerQueryActions(
        savedTags = savedTags,
        onSelect = { selected -> selection = selected },
        onAddAlternative = ::addAlternativeTo,
        onEdit = { action ->
            targetTerm = null
            selection = null
            onAction(action)
        },
        onRemove = { action, removed, excluded ->
            targetTerm = null
            selection = null
            removalUndo = composerRemovalUndo(terms, removed, excluded)
            onAction(action)
        },
    )

    ComposerEditorBody(
        state, target, targetIndex, terms, suggestions, liveSelection, inputRequester, snackbars,
        sourcePicker, savedSearches, savedTags, queryActions, onAction, onOpenSourceAccounts,
        onUndo, onReset, onManageSavedTags, onDismiss, onOpenFilters,
        onCommit = ::commitTyped,
        onSubmit = ::submitTyped,
        onToggleTarget = {
            if (target is ComposerAddTarget.Alternative) targetTerm = null else excludeNext = !excludeNext
            inputRequester.requestFocus()
        },
        onFieldFocused = { focused ->
            onAction(if (focused) SearchAction.RefreshAutocomplete else SearchAction.ClearAutocomplete)
        },
    )
}

@Composable
private fun ComposerRemovalUndoEffect(
    undo: ComposerRemovalUndo?,
    terms: ComposerTerms,
    snackbars: SnackbarHostState,
    onAction: (SearchAction) -> Unit,
    onFinished: () -> Unit,
) {
    LaunchedEffect(terms) {
        if (undo?.stillApplies(terms) == false) onFinished()
    }
    LaunchedEffect(undo) {
        if (undo == null) return@LaunchedEffect
        val result = snackbars.showSnackbar(undo.message, actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) {
            onAction(SearchAction.RestoreDraftTerms(undo.before.groups, undo.before.excluded))
        }
        onFinished()
    }
}

private fun commitComposerInput(
    target: ComposerAddTarget,
    input: String,
    canCommit: Boolean,
    clearFocus: () -> Unit,
    onAction: (SearchAction) -> Unit,
) {
    val action = composerTypedAction(target, input)
    if (action == null) {
        clearFocus()
    } else if (canCommit) {
        onAction(action)
        if (action is SearchAction.AddIncludeAlternative) onAction(SearchAction.ClearAutocomplete)
    }
}

@Composable
private fun ComposerEditorBody(
    state: SearchUiState,
    target: ComposerAddTarget,
    targetIndex: Int,
    terms: ComposerTerms,
    suggestions: ComposerSuggestions,
    liveSelection: SelectedTerm?,
    inputRequester: FocusRequester,
    snackbars: SnackbarHostState,
    sourcePicker: @Composable () -> Unit,
    savedSearches: ComposerSavedSearches,
    savedTags: ComposerSavedTags,
    queryActions: ComposerQueryActions,
    onAction: (SearchAction) -> Unit,
    onOpenSourceAccounts: () -> Unit,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onManageSavedTags: () -> Unit,
    onDismiss: () -> Unit,
    onOpenFilters: () -> Unit,
    onCommit: () -> Unit,
    onSubmit: () -> Unit,
    onToggleTarget: () -> Unit,
    onFieldFocused: (Boolean) -> Unit,
) {
    val input = state.suggestions.input
    val queryScroll = rememberScrollState()
    @Composable
    fun query(compact: Boolean, modifier: Modifier) = ComposerTagList(
        state, terms, targetIndex, liveSelection, compact, queryScroll, savedSearches, savedTags,
        onManageSavedTags, queryActions, onOpenSourceAccounts, onAction, modifier,
    )
    Column(Modifier.fillMaxSize()) {
        ComposerTopBar(state.canUndoSearch, state.canResetDraft, sourcePicker, onDismiss, onUndo, onReset, onOpenFilters)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compactMaxHeight = minOf(COMPACT_QUERY_MAX_HEIGHT, maxHeight * COMPACT_QUERY_MAX_FRACTION)
            Column(Modifier.fillMaxSize()) {
                if (suggestions.isVisible) {
                    query(compact = true, Modifier.fillMaxWidth().heightIn(max = compactMaxHeight))
                    HorizontalDivider()
                    ComposerSuggestionList(suggestions, target, onAction, onDismiss, Modifier.weight(1f).fillMaxWidth())
                } else {
                    query(compact = false, Modifier.weight(1f).fillMaxWidth())
                }
            }
            SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
        }
        if (state.query.supportedScopes.isNotEmpty() && input.isNotBlank()) {
            SearchScopeRow(
                scopes = state.query.supportedScopes,
                selectedScope = state.query.selectedScope,
                onScopeSelected = { scope -> onAction(SearchAction.SelectSuggestionScope(scope)) },
            )
        }
        ComposerField(
            input = input,
            target = target,
            validationMessage = state.validationMessage,
            limitNotices = state.query.termLimitNotices,
            canCommit = state.suggestions.canCommitInput,
            inputRequester = inputRequester,
            onToggleTarget = onToggleTarget,
            onFocusChanged = onFieldFocused,
            onValueChange = { value -> onAction(SearchAction.AutocompleteChanged(value)) },
            onCommit = onCommit,
            onSubmit = onSubmit,
        )
    }
}

@Composable
private fun ComposerTopBar(
    canUndo: Boolean,
    canReset: Boolean,
    sourcePicker: @Composable () -> Unit,
    onDismiss: () -> Unit,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close search editor") }
        sourcePicker()
        Box(Modifier.weight(1f))
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More search actions") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                @Composable
                fun item(label: String, enabled: Boolean, onClick: () -> Unit) = DropdownMenuItem(
                    text = { Text(label) },
                    enabled = enabled,
                    onClick = {
                        menuOpen = false
                        onClick()
                    },
                )
                item("Filter and sort", true, onOpenFilters)
                item("Undo last search", canUndo, onUndo)
                item("Reset", canReset, onReset)
            }
        }
    }
}

@Composable
private fun ComposerField(
    input: String,
    target: ComposerAddTarget,
    validationMessage: String?,
    limitNotices: List<SearchTermLimitNotice>,
    canCommit: Boolean,
    inputRequester: FocusRequester,
    onToggleTarget: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ComposerTargetButton(target, onToggleTarget)
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
            isError = validationMessage != null || limitNotices.any(SearchTermLimitNotice::blocksSearch),
            supportingText = (validationMessage ?: limitNotices.takeIf { it.isNotEmpty() }
                ?.joinToString(" ") { it.message() })?.let { message -> { Text(message) } },
            placeholder = {
                Text(
                    when (target) {
                        is ComposerAddTarget.Alternative -> "Add an alternative"
                        ComposerAddTarget.Excluded -> "Exclude a tag"
                        ComposerAddTarget.Required -> "Add a tag"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = KeyboardType.Text,
                capitalization = KeyboardCapitalization.None,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            trailingIcon = if (input.isNotBlank()) {
                { TextButton(onClick = onCommit, enabled = canCommit) { Text("Add") } }
            } else {
                null
            },
        )
    }
}

@Composable
private fun ComposerTargetButton(target: ComposerAddTarget, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (label, description, color) = when (target) {
        is ComposerAddTarget.Alternative ->
            Triple("or ✕", "Adding alternatives. Stop adding alternatives", colors.primary)
        ComposerAddTarget.Excluded ->
            Triple("− not", "Next tag is excluded. Switch to required", colors.error)
        ComposerAddTarget.Required ->
            Triple("+ and", "Next tag is required. Switch to excluded", colors.onSurface)
    }
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.testTag("Search add target").semantics { contentDescription = description },
    ) { Text(label, maxLines = 1) }
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
    suggestions: ComposerSuggestions,
    target: ComposerAddTarget,
    onAction: (SearchAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val placed = placeOmniboxMatches(suggestions.rows, suggestions.omnibox)
    LazyColumn(modifier) {
        items(placed.leading) { suggestion -> ComposerSuggestionRow(suggestion, onAction) }
        if (suggestions.hiddenCount > 0 && target is ComposerAddTarget.Alternative) {
            item {
                Text(
                    composerHiddenSuggestionsNote(suggestions.hiddenCount, target.group),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        omniboxRows(placed.matches, afterTags = placed.leading.isNotEmpty(), onLeave = onDismiss)
        if (placed.trailing.isNotEmpty()) item { HorizontalDivider() }
        items(placed.trailing) { suggestion -> ComposerSuggestionRow(suggestion, onAction) }
    }
}

@Composable
private fun ComposerSuggestionRow(suggestion: ComposerSuggestion, onAction: (SearchAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onAction(suggestion.add)
                onAction(SearchAction.ClearAutocomplete)
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (suggestion.saved) {
            Icon(Icons.Default.Star, "Saved tag", Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
        } else if (suggestion.searched) {
            Icon(Icons.Default.History, "Searched before", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
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

@Composable
private fun ComposerTagList(
    state: SearchUiState,
    terms: ComposerTerms,
    targetIndex: Int,
    selection: SelectedTerm?,
    compact: Boolean,
    scroll: ScrollState,
    savedSearches: ComposerSavedSearches,
    savedTags: ComposerSavedTags,
    onManageSavedTags: () -> Unit,
    queryActions: ComposerQueryActions,
    onOpenSourceAccounts: () -> Unit,
    onAction: (SearchAction) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ComposerQuery(terms, targetIndex, selection, queryActions)
        if (compact) return@Column
        ComposerSavedSection(savedSearches, savedTags, state.query.draft, targetIndex, onManageSavedTags, onAction)
        if (
            state.query.appliedSourceScope !is SearchSourceScope.Single &&
            visibleSourceStatusChipStatuses(state.content.statuses).isNotEmpty()
        ) {
            SourceStatusActions(state, onAction, onOpenSourceAccounts)
        }
    }
}

@Composable
private fun ComposerSavedSection(
    savedSearches: ComposerSavedSearches,
    savedTags: ComposerSavedTags,
    draft: Query,
    targetIndex: Int,
    onManageSavedTags: () -> Unit,
    onAction: (SearchAction) -> Unit,
) {
    val showSearches = savedSearches.entries.isNotEmpty() || savedSearches.action != null
    if (showSearches) SavedSearchesPart(savedSearches)
    if (savedTags.entries.isEmpty()) return
    if (showSearches) HorizontalDivider(Modifier.padding(top = 8.dp))
    SavedPartHeader("Saved tags") {
        if (savedTags.entries.isNotEmpty()) {
            TextButton(
                onClick = onManageSavedTags,
                modifier = Modifier.semantics { contentDescription = "Manage saved tags" },
            ) { Text("Manage") }
        }
    }
    SavedTagChips(savedTags, draft, targetIndex, onAction)
}

@Composable
private fun SavedSearchesPart(savedSearches: ComposerSavedSearches) {
    val action = savedSearches.action
    var naming by remember { mutableStateOf(false) }
    SavedPartHeader("Saved searches") {
        if (action is ComposerSavedSearchAction.Save) {
            TextButton(onClick = { naming = true }) {
                Icon(Icons.Default.BookmarkAdd, null, Modifier.size(18.dp))
                Text("Save", Modifier.padding(start = 4.dp))
            }
        }
        if (savedSearches.entries.isNotEmpty()) {
            TextButton(
                onClick = savedSearches.onManage,
                modifier = Modifier.semantics { contentDescription = "Manage saved searches" },
            ) { Text("Manage") }
        }
    }
    if (action is ComposerSavedSearchAction.Update) SavedSearchUpdateRow(action)
    if (savedSearches.entries.isNotEmpty()) SavedSearchChips(savedSearches)
    if (naming && action is ComposerSavedSearchAction.Save) {
        SavedSearchNameDialog(
            initialName = action.suggestedName,
            renaming = false,
            onDismiss = { naming = false },
            onConfirm = { name ->
                action.onSave(name)
                naming = false
            },
        )
    }
}

@Composable
private fun SavedSearchUpdateRow(update: ComposerSavedSearchAction.Update) {
    FilledTonalButton(
        onClick = { update.onUpdate?.invoke() },
        enabled = update.onUpdate != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Update “${update.name}”", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    update.conflict?.let { name ->
        Text(
            "“$name” already saves this search.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SavedPartHeader(title: String, actions: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { ComposerSectionTitle(title) }
        actions()
    }
}

@Composable
private fun SavedSearchChips(savedSearches: ComposerSavedSearches) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(savedSearches.entries, key = SavedSearchEntry::id) { entry ->
            FilterChip(
                selected = savedSearches.isCurrent(entry),
                onClick = { savedSearches.onOpen(entry) },
                label = { SavedSearchName(entry, Modifier.widthIn(max = SAVED_CHIP_MAX_WIDTH)) },
                leadingIcon = { Icon(Icons.Default.Bookmark, null, Modifier.size(16.dp)) },
            )
        }
    }
}

@Composable
private fun SavedTagChips(savedTags: ComposerSavedTags, draft: Query, targetIndex: Int, onAction: (SearchAction) -> Unit) {
    var menuFor by remember { mutableStateOf<FavoriteTagEntry?>(null) }
    var renaming by remember { mutableStateOf<FavoriteTagEntry?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(savedTags.entries, key = FavoriteTagEntry::key) { tag ->
            val chipState = tag.state(draft)
            val group = tag.tags.size > 1
            Box {
                TagPickerChip(
                    label = tag.label,
                    count = null,
                    state = chipState,
                    enabled = tag.canTap(draft, targetIndex),
                    tapLabel = if (group) savedGroupTapLabel(tag.label, chipState) else tagTapLabel(tag.label, chipState),
                    onTap = { tag.tapActions(draft, targetIndex).forEach(onAction) },
                    leadingIcon = Icons.Default.Star,
                    reserveCountSlot = false,
                    modifier = Modifier.widthIn(max = SAVED_CHIP_MAX_WIDTH),
                    onLongPress = { menuFor = tag },
                    longPressLabel = "Edit ${tag.label}",
                )
                SavedTagMenu(
                    expanded = menuFor == tag,
                    canRename = group,
                    onDismiss = { menuFor = null },
                    onRename = { menuFor = null; renaming = tag },
                    onRemove = { menuFor = null; savedTags.onRemove(tag.source, tag.tags) },
                )
            }
        }
    }
    renaming?.let { entry ->
        OrGroupNameDialog(entry, onDismiss = { renaming = null }) { name ->
            savedTags.onRename(entry.source, entry.tags, name)
            renaming = null
        }
    }
}

@Composable
internal fun OrGroupNameDialog(entry: FavoriteTagEntry, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    NameEntryDialog(
        title = "Name “or” group",
        initialName = entry.name.orEmpty(),
        optional = true,
        hint = "Leave blank to show it as ${entry.alternativesText}.",
        onDismiss = onDismiss,
        onSave = onSave,
    )
}

@Composable
private fun SavedTagMenu(expanded: Boolean, canRename: Boolean, onDismiss: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (canRename) {
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = onRename)
        }
        DropdownMenuItem(
            text = { Text("Remove from saved tags") },
            leadingIcon = { Icon(Icons.Default.Delete, null) },
            onClick = onRemove,
        )
    }
}
