package com.theoriacodex.app.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import com.theoriacodex.app.ui.components.FoldHeader
import com.theoriacodex.app.ui.components.fadeOutAtEnd
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.IconButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.theoriacodex.app.post.PostTagSelection
import com.theoriacodex.domain.model.Query
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import com.theoriacodex.app.search.alternativesText
import com.theoriacodex.app.search.label
import com.theoriacodex.app.search.searchTermChipLabel
import com.theoriacodex.app.search.state.SearchDraftReducer
import com.theoriacodex.app.source.displayName
import com.theoriacodex.data.repository.FavoriteTagEntry
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey

class PostTagSheetExtras(
    val tagsInitiallyCollapsed: Boolean,
    val scrollToAfterTags: Boolean = false,
    val scrollToKey: Any? = null,
    val filterBehindIcon: Boolean = false,
    val afterTags: LazyListScope.() -> Unit = {},
    val onFork: ((SearchTerm) -> Unit)? = null,
    val levelQuery: Query? = null,
)

@Composable
fun PostTagActionSection(
    post: Post,
    header: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
    loading: Boolean = false,
    tagVideoCountProvider: (SourceKey, String) -> Int? = LocalPostTagCounts.current.videoCount,
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = LocalPostTagCounts.current.fetchVideoCounts,
    onAddIncludeTerm: (SearchTerm) -> Boolean = LocalPostTagSearchActions.current.let { actions -> { term -> actions.addInclude(post, term) } },
    onAddExcludeTerm: (SearchTerm) -> Boolean = LocalPostTagSearchActions.current.let { actions -> { term -> actions.addExclude(post, term) } },
    onRemoveIncludeTerm: (SearchTerm) -> Unit = LocalPostTagSearchActions.current.let { actions -> { term -> actions.removeInclude(post, term) } },
    onRemoveExcludeTerm: (SearchTerm) -> Unit = LocalPostTagSearchActions.current.let { actions -> { term -> actions.removeExclude(post, term) } },
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)? = LocalPostTagSavedTags.current.add,
    draftQuery: Query? = null,
    onSearch: (() -> Unit)? = null,
    sheetExtras: PostTagSheetExtras? = null,
) {
    val termGroups = remember(post.taxonomy, post.canonicalTags, post.rawTags) {
        postActionTermGroups(post)
    }
    val translations = remember(post.taxonomy) { post.tagTranslations() }
    val draft = draftQuery ?: LocalSearchDraftSelection.current.collectAsStateWithLifecycle(initialValue = null).value
    val selection = remember(post.id.source, draft) {
        PostTagSelection(post.id.source, draft?.includeTerms.orEmpty(), draft?.excludeTerms.orEmpty())
    }
    val countOf = rememberPostTagCounts(post, termGroups, tagVideoCountProvider, fetchTagVideoCounts)
    var targetTerm by remember(post.id) { mutableStateOf<SearchTerm?>(null) }
    var pendingSwitch by remember(post.id) { mutableStateOf<PendingSourceSwitch?>(null) }
    var expandedGroups by remember(post.id) { mutableStateOf(emptySet<String>()) }
    val terms = remember(termGroups) { termGroups.flatMap(PostActionTermGroup::terms) }
    var tagsCollapsed by remember(post.id) { mutableStateOf(sheetExtras?.tagsInitiallyCollapsed == true) }
    val foldsTags = sheetExtras != null && !loading && terms.isNotEmpty()
    val collapsed = foldsTags && tagsCollapsed
    val filter = rememberTagFilter(post.id, termGroups, translations, foldsTags && sheetExtras.filterBehindIcon) { tagsCollapsed = false }
    val draftGroups = draft?.effectiveIncludeTermGroups.orEmpty()
    val targetGroup = draftGroups.indexOfFirst { group -> targetTerm in group.terms }.takeIf { it >= 0 }
    val searchActions = LocalPostTagSearchActions.current
    val editor = PostTagEditor(
        post, draft, selection, targetGroup,
        onAddInclude = onAddIncludeTerm,
        onAddExclude = onAddExcludeTerm,
        onRemoveInclude = onRemoveIncludeTerm,
        onRemoveExclude = onRemoveExcludeTerm,
        onAddAlternative = { groupIndex, term -> searchActions.addAlternative(post, groupIndex, term) },
        onConfirmSwitch = { pendingSwitch = it },
        extras = sheetExtras,
    )

    Column(Modifier.fillMaxWidth()) {
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = if (sheetExtras?.scrollToAfterTags == true && collapsed) AFTER_FOLDED_TAGS_INDEX else 0,
        )
        val chip = rememberTagChip(editor, translations, countOf, onFavoriteTagLongPress)
        val keyIndex = remember { LazyListKeyIndex() }
        sheetExtras?.scrollToKey?.let { key -> ScrollToKeyEffect(key, listState, keyIndex) }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val items = keyIndex.tracking(this)
            items.item(key = "header") { header() }
            if (loading) {
                items.item(key = "loading") { Text("Loading tags...", style = MaterialTheme.typography.bodySmall) }
            } else {
                val fold = TagFold(foldsTags, tagsCollapsed) { tagsCollapsed = !tagsCollapsed }
                items.postTagSections(filter.groups, terms, expandedGroups, { label -> expandedGroups = expandedGroups + label }, fold, filter, sheetExtras, chip)
            }
            items.item(key = "footer") { footer() }
        }
        PostTagQueryTray(
            draft, targetGroup, onSearch,
            onTargetGroup = { index -> targetTerm = index?.let { draftGroups[it].terms.first() } },
            confirmation = pendingSwitch?.let { pending -> pending.confirmation(post.id.source, editor) { pendingSwitch = null } },
        )
    }
}

@Composable
private fun rememberTagChip(
    editor: PostTagEditor,
    translations: Map<String, String>,
    countOf: (SearchTerm) -> Int?,
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)?,
): @Composable (SearchTerm, Boolean) -> Unit {
    val savedTags = LocalPostTagSavedTags.current
    val saved by savedTags.saved.collectAsStateWithLifecycle(initialValue = emptySet())
    return { term, compact ->
        val isSaved = term.isGeneralTag && (editor.post.id.source to term.value) in saved
        PostTagChip(term, translations[term.value], countOf(term), editor, isSaved, onFavoriteTagLongPress, savedTags.remove, compact)
    }
}

@Composable
private fun ScrollToKeyEffect(key: Any, listState: LazyListState, keyIndex: LazyListKeyIndex) {
    LaunchedEffect(key) {
        val index = snapshotFlow { listState.layoutInfo.totalItemsCount }.mapNotNull { keyIndex.indexOf(key) }.first()
        listState.scrollToItem(index)
    }
}

@Composable
private fun rememberPostTagCounts(
    post: Post,
    termGroups: List<PostActionTermGroup>,
    tagVideoCountProvider: (SourceKey, String) -> Int?,
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?>,
): (SearchTerm) -> Int? {
    val terms = remember(termGroups) { termGroups.flatMap(PostActionTermGroup::terms) }
    val generalTags = remember(terms) {
        terms.filter(SearchTerm::isGeneralTag).map(SearchTerm::value).distinct()
    }
    val suppliedCounts = remember(post.taxonomy) { postActionSuppliedCounts(post) }
    val generalCounts = remember(suppliedCounts) {
        suppliedCounts.filterKeys(SearchTerm::isGeneralTag).mapKeys { it.key.value }
    }
    val tagVideoCounts = rememberTagCounts(post.id.source, generalTags, tagVideoCountProvider, fetchTagVideoCounts, generalCounts)
    val typedCounts = rememberSearchTermCounts(post.id.source, terms, suppliedCounts)
    return { term -> if (term.isGeneralTag) tagVideoCounts[term.value] else typedCounts[term] }
}

private class PendingSourceSwitch(val term: SearchTerm, val excluded: Boolean, val lost: List<SearchTerm>) {
    fun message(source: SourceKey): String {
        val removed = lost.joinToString(", ") { searchTermChipLabel(it, excluded = false) }
        return "${if (excluded) "Excluding" else "Adding"} ${term.value} switches Search to ${source.displayName()} and removes $removed."
    }

    fun confirmation(source: SourceKey, editor: PostTagEditor, onClose: () -> Unit) = TrayConfirmation(
        message = message(source),
        confirmLabel = "Switch source",
        dismissLabel = "Keep",
        onConfirm = {
            editor.commit(term, excluded)
            onClose()
        },
        onDismiss = onClose,
    )
}

private class PostTagEditor(
    val post: Post,
    val draft: Query?,
    val selection: PostTagSelection,
    val targetGroup: Int?,
    val onAddInclude: (SearchTerm) -> Boolean,
    val onAddExclude: (SearchTerm) -> Boolean,
    val onRemoveInclude: (SearchTerm) -> Unit,
    val onRemoveExclude: (SearchTerm) -> Unit,
    val onAddAlternative: (Int, SearchTerm) -> Boolean,
    val onConfirmSwitch: (PendingSourceSwitch) -> Unit,
    private val extras: PostTagSheetExtras?,
) {
    val onFork: ((SearchTerm) -> Unit)? get() = extras?.onFork

    val levelSelection: PostTagSelection? = extras?.levelQuery?.let { PostTagSelection(post.id.source, it.includeTerms, it.excludeTerms) }

    fun commit(term: SearchTerm, excluded: Boolean): Boolean =
        if (excluded) onAddExclude(term) else onAddInclude(term)

    private fun add(term: SearchTerm, excluded: Boolean, onAccepted: () -> Unit = {}) {
        val lost = draft?.let { SearchDraftReducer.termsLostToPostTerm(it, post.id.source, term) }.orEmpty()
        when {
            lost.isNotEmpty() -> onConfirmSwitch(PendingSourceSwitch(term, excluded, lost))
            commit(term, excluded) -> onAccepted()
        }
    }

    fun tap(term: SearchTerm) {
        when (val effect = tagTapEffect(term, selection, targetGroup)) {
            TagTapEffect.AddRequired -> add(term, excluded = false)
            is TagTapEffect.AddAlternative -> onAddAlternative(effect.groupIndex, term)
            is TagTapEffect.SwitchToExcluded -> add(effect.stored, excluded = true) { onRemoveInclude(effect.stored) }
            is TagTapEffect.Clear -> onRemoveExclude(effect.stored)
        }
    }

    fun canTap(term: SearchTerm): Boolean = targetGroup == null || selection.chipState(term) != TagChipState.NONE ||
        draft?.let { SearchDraftReducer.postTermForGroup(it, post.id.source, targetGroup, term) } != null

    fun alternateActions(term: SearchTerm): List<TagChipAction> {
        val name = searchTermChipLabel(term, excluded = false)
        return when (selection.chipState(term)) {
            TagChipState.NONE -> listOf(TagChipAction("Exclude $name from Search") { add(term, excluded = true) })
            TagChipState.REQUIRED -> listOf(
                TagChipAction("Remove $name from Search") { selection.included(term)?.let(onRemoveInclude) },
            )
            TagChipState.EXCLUDED -> listOf(
                TagChipAction("Include $name in Search") {
                    selection.excluded(term)?.let { stored -> add(stored, excluded = false) { onRemoveExclude(stored) } }
                },
            )
        }
    }
}

@Composable
private fun PostTagChip(
    term: SearchTerm,
    translation: String?,
    count: Int?,
    editor: PostTagEditor,
    saved: Boolean,
    onFavoriteLongPress: ((SourceKey, String) -> Unit)?,
    onRemoveSaved: (SourceKey, String) -> Unit,
    compact: Boolean,
) {
    val nativeName = searchTermChipLabel(term, excluded = false)
    val name = translation?.let { "$it ($nativeName)" } ?: nativeName
    val state = editor.selection.chipState(term)
    val favorite = onFavoriteLongPress?.takeIf { term.isGeneralTag }
    val haptics = LocalHapticFeedback.current
    val toggleSaved = favorite?.let { save ->
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (saved) onRemoveSaved(editor.post.id.source, term.value) else save(editor.post.id.source, term.value)
        }
    }
    val savedLabel = favorite?.let { if (saved) "Remove $name from saved tags" else "Save $name" }
    val shownCount = count?.takeUnless { compact }?.let(::compactTagCount)
    val icon = if (saved) Icons.Default.Star else null
    val fork = editor.onFork?.takeIf { term.isGeneralTag }
    if (fork != null) {
        val moves = listOfNotNull(TagChipAction(tagTapLabel(name, state)) { editor.tap(term) }.takeIf { editor.canTap(term) }) +
            editor.alternateActions(term) + listOfNotNull(toggleSaved?.let { TagChipAction(savedLabel.orEmpty(), it) })
        val active = editor.levelSelection?.chipState(term)?.takeIf { it != TagChipState.NONE } ?: state
        TagForkChip(name, postTagChipLabel(term.value, translation), shownCount, compact, active, icon, "saved".takeIf { saved }, moves) { fork(term) }
        return
    }
    TagPickerChip(
        label = postTagChipLabel(term.value, translation),
        count = shownCount,
        compact = compact,
        state = state,
        enabled = editor.canTap(term),
        tapLabel = tagTapLabel(name, state),
        onTap = { editor.tap(term) },
        alternateActions = editor.alternateActions(term),
        onLongPress = toggleSaved,
        longPressLabel = savedLabel,
        leadingIcon = icon,
        stateSuffix = "saved".takeIf { saved },
    )
}

@Composable
fun FavoriteTagActionGrid(
    source: SourceKey,
    entries: List<FavoriteTagEntry>,
    tagVideoCountProvider: (SourceKey, String) -> Int? = LocalPostTagCounts.current.videoCount,
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = LocalPostTagCounts.current.fetchVideoCounts,
    emptyText: String = "No saved tags",
    onAddTag: (FavoriteTagEntry) -> Unit,
    onRemoveTag: (FavoriteTagEntry) -> Unit,
    onRenameTag: ((FavoriteTagEntry) -> Unit)? = null,
) {
    val singleTags = remember(entries) { entries.mapNotNull { it.tags.singleOrNull() } }
    val tagVideoCounts = rememberTagCounts(source, singleTags, tagVideoCountProvider, fetchTagVideoCounts)

    if (entries.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth.value >= 354f) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    entries.chunked(columns).forEach { rowTags ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            rowTags.forEach { entry ->
                FavoriteTagActionCell(
                    tag = entry.label,
                    detail = entry.tags.singleOrNull()?.let { tagVideoCounts[it]?.toString() }
                        ?: entry.name?.let { entry.alternativesText }.orEmpty(),
                    onAdd = { onAddTag(entry) },
                    onRemove = { onRemoveTag(entry) },
                    onRename = onRenameTag?.takeIf { entry.tags.size > 1 }?.let { rename -> { rename(entry) } },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(columns - rowTags.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
        }
    }
}

@Composable
private fun rememberTagCounts(source: SourceKey, tags: List<String>,
    cached: (SourceKey, String) -> Int?, fetch: suspend (SourceKey, List<String>) -> Map<String, Int?>,
    supplied: Map<String, Int> = emptyMap()): Map<String, Int?> {
    var counts by remember(source, tags, supplied) { mutableStateOf<Map<String, Int?>>(supplied) }
    val currentCached by rememberUpdatedState(cached)
    val currentFetch by rememberUpdatedState(fetch)
    LaunchedEffect(source, tags, supplied) {
        counts = withContext(Dispatchers.Default) { tags.associateWith { supplied[it] ?: currentCached(source, it) } }
        val missing = tags.filter { counts[it] == null }
        if (missing.isNotEmpty()) counts = counts + withContext(Dispatchers.IO) { currentFetch(source, missing) }
    }
    return counts
}

private fun LazyListScope.postTagList(
    groups: List<PostActionTermGroup>,
    expandedGroups: Set<String>,
    onExpandGroup: (String) -> Unit,
    filter: TagFilter,
    chip: @Composable (SearchTerm, Boolean) -> Unit,
) {
    if (groups.isEmpty()) {
        item(key = "empty") {
            Text(
                text = if (filter.active) "No tags match \"${filter.text.trim()}\"" else "No search terms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    groups.forEach { group ->
        item(key = "group:${group.label}", contentType = "group") {
            Text(
                text = group.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        val collapsed = !filter.active && group.label !in expandedGroups && group.terms.size > COLLAPSE_TAGS_OVER
        val rows = (if (collapsed) group.terms.take(COLLAPSED_TAG_COUNT) else group.terms).tagChipChunks()
        items(rows.size, key = { "row:${group.label}:$it" }, contentType = { "tags" }) { rowIndex ->
            TagChipRow {
                rows[rowIndex].forEach { term -> chip(term, false) }
                if (collapsed && rowIndex == rows.lastIndex) {
                    TextButton(onClick = { onExpandGroup(group.label) }) {
                        Text("+${group.terms.size - COLLAPSED_TAG_COUNT} more")
                    }
                }
            }
        }
    }
}

@Composable
private fun PostTagQueryTray(
    draft: Query?,
    targetGroup: Int?,
    onSearch: (() -> Unit)?,
    onTargetGroup: (Int?) -> Unit,
    confirmation: TrayConfirmation?,
) = QueryTray(
    title = "Your search",
    emptyText = "No tags",
    query = draft,
    targetGroup = targetGroup,
    onTargetGroup = onTargetGroup,
    onSearch = onSearch,
    confirmation = confirmation,
)

private class TagFold(val enabled: Boolean, val collapsed: Boolean, val onToggle: () -> Unit)

private class TagFilter(
    val groups: List<PostActionTermGroup>,
    val available: Boolean,
    val behindIcon: Boolean,
    val open: Boolean,
    val text: String,
    val focus: Boolean,
    val onText: (String) -> Unit,
    val onOpen: () -> Unit,
    val onClose: () -> Unit,
    val onFocused: () -> Unit,
) {
    val active: Boolean get() = text.isNotBlank()
}

@Composable
private fun rememberTagFilter(
    postId: PostId,
    termGroups: List<PostActionTermGroup>,
    translations: Map<String, String>,
    behindIcon: Boolean,
    onOpen: () -> Unit,
): TagFilter {
    var text by remember(postId) { mutableStateOf("") }
    var open by remember(postId) { mutableStateOf(false) }
    var focus by remember(postId) { mutableStateOf(false) }
    val normalized = text.normalizedTagFilterText()
    val groups = remember(termGroups, translations, normalized) { filterPostTagGroups(termGroups, normalized, translations) }
    return TagFilter(
        groups = groups,
        available = termGroups.sumOf { it.terms.size } > COLLAPSE_TAGS_OVER,
        behindIcon = behindIcon,
        open = !behindIcon || open,
        text = text,
        focus = focus,
        onText = { text = it },
        onOpen = {
            open = true
            focus = true
            onOpen()
        },
        onClose = {
            open = false
            focus = false
        },
        onFocused = { focus = false },
    )
}

internal fun filterPostTagGroups(
    groups: List<PostActionTermGroup>,
    normalizedFilter: String,
    translations: Map<String, String>,
): List<PostActionTermGroup> {
    if (normalizedFilter.isEmpty()) return groups
    fun matches(term: SearchTerm) = normalizedFilter in term.value.normalizedTagFilterText() ||
        translations[term.value]?.normalizedTagFilterText()?.contains(normalizedFilter) == true
    return groups.mapNotNull { group -> group.terms.filter(::matches).takeIf { it.isNotEmpty() }?.let { group.copy(terms = it) } }
}

@Composable
private fun PostTagFilterField(filter: TagFilter) {
    val focusRequester = remember { FocusRequester() }
    TagFilterField(
        value = filter.text,
        onValueChange = filter.onText,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).focusRequester(focusRequester),
        onClose = if (filter.behindIcon) filter.onClose else null,
    )
    if (filter.focus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            filter.onFocused()
        }
    }
}

private fun LazyListScope.postTagSections(
    groups: List<PostActionTermGroup>,
    terms: List<SearchTerm>,
    expandedGroups: Set<String>,
    onExpandGroup: (String) -> Unit,
    fold: TagFold,
    filter: TagFilter,
    sheetExtras: PostTagSheetExtras?,
    chip: @Composable (SearchTerm, Boolean) -> Unit,
) {
    if (fold.enabled) {
        item(key = "tags-header") {
            FoldHeader(
                "Tags", "tags", terms.size.toString(), fold.collapsed, fold.onToggle,
                trailing = if (filter.available && filter.behindIcon && !filter.open) {
                    { IconButton(onClick = filter.onOpen) { Icon(Icons.Default.Search, contentDescription = "Filter tags") } }
                } else {
                    null
                },
            )
        }
    }
    if (fold.enabled && fold.collapsed) {
        item(key = "tags-peek") { TagPeekRow(terms.take(PEEK_TAG_COUNT), chip) }
    } else {
        if (filter.available && filter.open) item(key = "tag-filter") { PostTagFilterField(filter) }
        postTagList(groups, expandedGroups, onExpandGroup, filter, chip)
    }
    sheetExtras?.afterTags?.invoke(this)
}

@Composable
private fun TagPeekRow(terms: List<SearchTerm>, chip: @Composable (SearchTerm, Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fadeOutAtEnd()
            .horizontalScroll(rememberScrollState(), enabled = false),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        terms.forEach { term -> chip(term, true) }
    }
}

@Composable
private fun FavoriteTagActionCell(
    tag: String,
    detail: String,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onRename: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TagSelectionSurface(
            tag = tag,
            active = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = detail,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TagActionPill(
                actionLabel = "Include $tag in Search",
                icon = { Text("+", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = onAdd,
                modifier = Modifier.weight(1f),
            )
            onRename?.let { rename ->
                TagActionPill(
                    actionLabel = "Name $tag",
                    icon = { Icon(imageVector = Icons.Default.Edit, contentDescription = null) },
                    onClick = rename,
                    modifier = Modifier.weight(1f),
                )
            }
            TagActionPill(
                actionLabel = "Remove $tag from saved tags",
                icon = { Icon(imageVector = Icons.Default.Delete, contentDescription = null) },
                onClick = onRemove,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun TagSelectionSurface(
    tag: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    longPressModifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.then(longPressModifier),
        shape = RoundedCornerShape(999.dp),
        color = if (active) {
            accent.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
    ) {
        Text(
            text = tag,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun TagActionPill(
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = actionLabel }
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), Alignment.Center) { icon() }
    }
}

private const val AFTER_FOLDED_TAGS_INDEX = 3
private const val PEEK_TAG_COUNT = 10
private const val COLLAPSE_TAGS_OVER = 16
private const val COLLAPSED_TAG_COUNT = 12
