package com.theoriacodex.app.tags

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import com.theoriacodex.app.search.alternativesText
import com.theoriacodex.app.search.label
import com.theoriacodex.app.search.searchScopeLabel
import com.theoriacodex.app.search.searchTermChipLabel
import com.theoriacodex.app.search.state.SearchDraftReducer
import com.theoriacodex.app.source.displayName
import com.theoriacodex.data.repository.FavoriteTagEntry
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey

/**
 * A post's tags beside the search they feed. Tapping a tag edits the live Search draft, and the tray
 * below shows the result. [onSearch] runs the draft; it is null where this screen cannot reach Search.
 */
@Composable
fun PostTagActionSection(
    post: Post,
    header: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
    loading: Boolean = false,
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    onAddIncludeTerm: (SearchTerm) -> Boolean,
    onAddExcludeTerm: (SearchTerm) -> Boolean,
    onRemoveIncludeTerm: (SearchTerm) -> Unit,
    onRemoveExcludeTerm: (SearchTerm) -> Unit,
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)? = null,
    draftQuery: Query? = null,
    onSearch: (() -> Unit)? = null,
) {
    val termGroups = remember(post.taxonomy, post.canonicalTags, post.rawTags) {
        postActionTermGroups(post)
    }
    val draft = draftQuery ?: LocalSearchDraftSelection.current.collectAsStateWithLifecycle(initialValue = null).value
    val selection = remember(post.id.source, draft) {
        PostTagSelection(post.id.source, draft?.includeTerms.orEmpty(), draft?.excludeTerms.orEmpty())
    }
    val countOf = rememberPostTagCounts(post, termGroups, tagVideoCountProvider, fetchTagVideoCounts)
    var targetTerm by remember(post.id) { mutableStateOf<SearchTerm?>(null) }
    var pendingSwitch by remember(post.id) { mutableStateOf<PendingSourceSwitch?>(null) }
    var expandedGroups by remember(post.id) { mutableStateOf(emptySet<String>()) }
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
    )

    Column(Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item(key = "header") { header() }
            if (loading) {
                item(key = "loading") { Text("Loading tags...", style = MaterialTheme.typography.bodySmall) }
            } else {
                postTagList(
                    groups = termGroups,
                    expandedGroups = expandedGroups,
                    onExpandGroup = { label -> expandedGroups = expandedGroups + label },
                ) { term -> PostTagChip(term, countOf(term), editor, onFavoriteTagLongPress) }
            }
            item(key = "footer") { footer() }
        }
        QueryTray(
            title = "Your search",
            emptyText = "Tap a tag to start a search.",
            query = draft,
            targetGroup = targetGroup,
            onTargetGroup = { index -> targetTerm = index?.let { draftGroups[it].terms.first() } },
            onSearch = onSearch,
            confirmation = pendingSwitch?.let { pending ->
                pending.confirmation(post.id.source, editor) { pendingSwitch = null }
            },
        )
    }
}

/** Looks up each tag's count: general tags by name, typed tags by their full identity. */
@Composable
private fun rememberPostTagCounts(
    post: Post,
    termGroups: List<PostActionTermGroup>,
    tagVideoCountProvider: (SourceKey, String) -> Int?,
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?>,
): (SearchTerm) -> Int? {
    val terms = remember(termGroups) { termGroups.flatMap(PostActionTermGroup::terms) }
    val generalTags = remember(terms) {
        terms.filter(SearchTerm::isGeneralPostTag).map(SearchTerm::value).distinct()
    }
    val suppliedCounts = remember(post.taxonomy) { postActionSuppliedCounts(post) }
    val generalCounts = remember(suppliedCounts) {
        suppliedCounts.filterKeys(SearchTerm::isGeneralPostTag).mapKeys { it.key.value }
    }
    val tagVideoCounts = rememberTagCounts(post.id.source, generalTags, tagVideoCountProvider, fetchTagVideoCounts, generalCounts)
    val typedCounts = rememberSearchTermCounts(post.id.source, terms, suppliedCounts)
    return { term -> if (term.isGeneralPostTag()) tagVideoCounts[term.value] else typedCounts[term] }
}

/** A typed tag from another source, waiting because adding it would drop terms from the draft. */
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

/** Turns chip gestures into edits of the Search draft, holding back any that would lose terms. */
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
) {
    fun commit(term: SearchTerm, excluded: Boolean): Boolean =
        if (excluded) onAddExclude(term) else onAddInclude(term)

    /**
     * Every addition passes here, so none can drop terms unasked. [onAccepted] removes an opposing
     * entry only once the draft takes the new one; a confirmed switch has already dropped it.
     */
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

    /** The states TalkBack can reach directly, leaving out what a tap on the chip already does. */
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
    count: Int?,
    editor: PostTagEditor,
    onFavoriteLongPress: ((SourceKey, String) -> Unit)?,
) {
    val name = searchTermChipLabel(term, excluded = false)
    val state = editor.selection.chipState(term)
    val favorite = onFavoriteLongPress?.takeIf { term.isGeneralPostTag() }
    TagPickerChip(
        label = term.value,
        count = count?.let(::compactTagCount),
        state = state,
        enabled = editor.canTap(term),
        tapLabel = tagTapLabel(name, state),
        onTap = { editor.tap(term) },
        alternateActions = editor.alternateActions(term),
        onLongPress = favorite?.let { { it(editor.post.id.source, term.value) } },
        longPressLabel = favorite?.let { "Add $name to favorites" },
    )
}

/**
 * Saved tags and "or" groups with actions to search for them or remove them. Single tags show
 * counts; a named group shows its alternatives instead, and groups can be renamed when [onRenameTag] is set.
 */
@Composable
fun FavoriteTagActionGrid(
    source: SourceKey,
    entries: List<FavoriteTagEntry>,
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    emptyText: String = "No favorite tags",
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

/** Both tag surfaces publish local counts first and keep all enrichment off the UI dispatcher. */
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

/** Type headings over wrapping chips; a long group shows its first tags until it is expanded. */
private fun LazyListScope.postTagList(
    groups: List<PostActionTermGroup>,
    expandedGroups: Set<String>,
    onExpandGroup: (String) -> Unit,
    chip: @Composable (SearchTerm) -> Unit,
) {
    if (groups.isEmpty()) {
        item(key = "empty") {
            Text(
                text = "No search terms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    item(key = "hint") {
        Text(
            text = "Tap a tag to add it, again to exclude it, and once more to clear it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
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
        val collapsed = group.label !in expandedGroups && group.terms.size > COLLAPSE_TAGS_OVER
        val rows = (if (collapsed) group.terms.take(COLLAPSED_TAG_COUNT) else group.terms).tagChipChunks()
        items(rows.size, key = { "row:${group.label}:$it" }, contentType = { "tags" }) { rowIndex ->
            TagChipRow {
                rows[rowIndex].forEach { term -> chip(term) }
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
                selected = false,
                onClick = onAdd,
                modifier = Modifier.weight(1f),
            )
            onRename?.let { rename ->
                TagActionPill(
                    actionLabel = "Name $tag",
                    icon = { Icon(imageVector = Icons.Default.Edit, contentDescription = null) },
                    selected = false,
                    onClick = rename,
                    modifier = Modifier.weight(1f),
                )
            }
            TagActionPill(
                actionLabel = "Remove $tag from favorites",
                icon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove favorite tag",
                    )
                },
                selected = false,
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
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
            this.selected = selected
            contentDescription = actionLabel
            stateDescription = if (selected) "Selected" else "Not selected"
        }.clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            accent.copy(alpha = 0.24f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), Alignment.Center) { icon() }
    }
}

internal data class PostActionTermGroup(
    val label: String,
    val terms: List<SearchTerm>,
)

/** Keep supplied counts keyed by the same typed identity used by the action grid. */
internal fun postActionSuppliedCounts(post: Post): Map<SearchTerm, Int> = buildMap {
    post.taxonomy.forEach { taxonomy ->
        val term = taxonomy.toSearchTerm().normalizedPostActionTermOrNull()
        val count = taxonomy.count?.takeIf { it >= 0 }
        if (term != null && count != null) put(term, count)
    }
}

internal fun postActionTerms(post: Post): List<SearchTerm> {
    val typedTerms = post.taxonomy
        .mapNotNull { taxonomyTerm -> taxonomyTerm.toSearchTerm().normalizedPostActionTermOrNull() }
        .distinct()
    if (typedTerms.isNotEmpty()) return typedTerms

    val legacyTags = post.canonicalTags
        .ifEmpty { post.rawTags }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    return legacyTags.map { value -> SearchTerm(value = value) }
}

internal fun postActionTermGroups(post: Post): List<PostActionTermGroup> {
    return postActionTerms(post)
        .groupBy(SearchTerm::postActionGroupKey)
        .entries
        .sortedWith(
            compareBy<Map.Entry<PostActionGroupKey, List<SearchTerm>>> { entry ->
                postTagFacetOrder.indexOf(entry.key.facet)
            }.thenBy { entry -> entry.key.namespaceOrder }
                .thenBy { entry -> entry.key.sourceNamespace.orEmpty() },
        )
        .map { (key, terms) ->
            PostActionTermGroup(
                label = searchScopeLabel(
                    FacetedSearchScope(
                        facet = key.facet,
                        sourceNamespace = key.sourceNamespace,
                    ),
                ),
                terms = terms,
            )
        }
}

internal fun generalPostActionTags(post: Post): List<String> {
    return postActionTerms(post)
        .filter(SearchTerm::isGeneralPostTag)
        .map(SearchTerm::value)
        .distinct()
}

internal fun SearchTerm.isGeneralPostTag(): Boolean {
    return facet == SearchFacet.TAG && (
        sourceNamespace == null || sourceNamespace.equals(GENERAL_TAG_NAMESPACE, ignoreCase = true)
    )
}

private data class PostActionGroupKey(
    val facet: SearchFacet,
    val sourceNamespace: String?,
) {
    val namespaceOrder: Int
        get() = when (sourceNamespace?.lowercase()) {
            null, GENERAL_TAG_NAMESPACE -> 0
            "female" -> 1
            "male" -> 2
            else -> 3
        }
}

private fun SearchTerm.postActionGroupKey(): PostActionGroupKey {
    val groupNamespace = sourceNamespace
        ?.takeUnless { namespace -> facet == SearchFacet.TAG && namespace.equals(GENERAL_TAG_NAMESPACE, true) }
    return PostActionGroupKey(facet = facet, sourceNamespace = groupNamespace)
}

private fun SearchTerm.normalizedPostActionTermOrNull(): SearchTerm? {
    val normalizedValue = value.trim().takeIf(String::isNotBlank) ?: return null
    val normalizedNamespace = sourceNamespace?.trim()?.takeIf(String::isNotBlank)
    return copy(value = normalizedValue, sourceNamespace = normalizedNamespace)
}

/** The most identifying types come first, since they tend to make the most useful searches. */
internal val postTagFacetOrder = listOf(
    SearchFacet.ARTIST,
    SearchFacet.SERIES,
    SearchFacet.CHARACTER,
    SearchFacet.TAG,
    SearchFacet.GROUP,
    SearchFacet.TYPE,
    SearchFacet.LANGUAGE,
)
private const val GENERAL_TAG_NAMESPACE = "tag"
private const val COLLAPSE_TAGS_OVER = 16
private const val COLLAPSED_TAG_COUNT = 12
