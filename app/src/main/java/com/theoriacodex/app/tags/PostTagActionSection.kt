package com.theoriacodex.app.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.theoriacodex.app.search.searchScopeLabel
import com.theoriacodex.app.search.searchTermChipLabel
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey

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
) {
    val termGroups = remember(post.taxonomy, post.canonicalTags, post.rawTags) {
        postActionTermGroups(post)
    }
    val terms = remember(termGroups) { termGroups.flatMap(PostActionTermGroup::terms) }
    val generalTags = remember(terms) {
        terms.filter(SearchTerm::isGeneralPostTag).map(SearchTerm::value).distinct()
    }
    val draft = draftQuery ?: LocalSearchDraftSelection.current.collectAsStateWithLifecycle(initialValue = null).value
    val selection = remember(post.id.source, draft) {
        PostTagSelection(post.id.source, draft?.includeTerms.orEmpty(), draft?.excludeTerms.orEmpty())
    }
    val tagVideoCounts = rememberTagCounts(post.id.source, generalTags, tagVideoCountProvider, fetchTagVideoCounts)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth.value >= 354f) 3 else 2
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") { header() }
        item(key = "search-terms") { Text("Search terms", style = MaterialTheme.typography.titleSmall) }
        if (loading) {
            item(key = "loading") { Text("Loading tags...", style = MaterialTheme.typography.bodySmall) }
        } else {
            tagActionGrid(
                groups = termGroups,
                columns = columns,
                videoCounts = tagVideoCounts,
                selection = selection,
                onFavoriteTagLongPress = if (onFavoriteTagLongPress != null) {
                    { tag -> onFavoriteTagLongPress(post.id.source, tag) }
                } else {
                    null
                },
                onIncludeTerm = { term ->
                    val stored = selection.included(term)
                    if (stored != null) onRemoveIncludeTerm(stored) else onAddIncludeTerm(term)
                },
                onExcludeTerm = { term ->
                    val stored = selection.excluded(term)
                    if (stored != null) onRemoveExcludeTerm(stored) else onAddExcludeTerm(term)
                },
            )
        }
        item(key = "footer") { footer() }
    }
    }
}

@Composable
fun FavoriteTagActionGrid(
    source: SourceKey,
    tags: List<String>,
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    emptyText: String = "No favorite tags",
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
) {
    val tagVideoCounts = rememberTagCounts(source, tags, tagVideoCountProvider, fetchTagVideoCounts)

    if (tags.isEmpty()) {
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
    tags.chunked(columns).forEach { rowTags ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            rowTags.forEach { tag ->
                FavoriteTagActionCell(
                    tag = tag,
                    videoCount = tagVideoCounts[tag],
                    onAdd = { onAddTag(tag) },
                    onRemove = { onRemoveTag(tag) },
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
    cached: (SourceKey, String) -> Int?, fetch: suspend (SourceKey, List<String>) -> Map<String, Int?>): Map<String, Int?> {
    var counts by remember(source, tags) { mutableStateOf<Map<String, Int?>>(emptyMap()) }
    val currentCached by rememberUpdatedState(cached)
    val currentFetch by rememberUpdatedState(fetch)
    LaunchedEffect(source, tags) {
        counts = withContext(Dispatchers.Default) { tags.associateWith { currentCached(source, it) } }
        val missing = tags.filter { counts[it] == null }
        if (missing.isNotEmpty()) counts = counts + withContext(Dispatchers.IO) { currentFetch(source, missing) }
    }
    return counts
}

private fun LazyListScope.tagActionGrid(
    groups: List<PostActionTermGroup>,
    columns: Int,
    videoCounts: Map<String, Int?>,
    selection: PostTagSelection,
    onFavoriteTagLongPress: ((String) -> Unit)?,
    onIncludeTerm: (SearchTerm) -> Unit,
    onExcludeTerm: (SearchTerm) -> Unit,
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

    groups.forEachIndexed { groupIndex, group ->
        item(key = "group:$groupIndex", contentType = "group") {
            Text(
                text = group.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val rows = group.terms.chunked(columns)
        items(rows.size, key = { "row:$groupIndex:$it" }, contentType = { "tags" }) { rowIndex ->
            val rowTerms = rows[rowIndex]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                rowTerms.forEach { term ->
                    val generalTag = term.takeIf(SearchTerm::isGeneralPostTag)?.value
                    TagActionCell(
                        label = postActionTermLabel(term),
                        videoCount = generalTag?.let(videoCounts::get),
                        includeSelected = selection.included(term) != null,
                        excludeSelected = selection.excluded(term) != null,
                        onTagLongPress = if (generalTag != null && onFavoriteTagLongPress != null) {
                            { onFavoriteTagLongPress(generalTag) }
                        } else {
                            null
                        },
                        onInclude = { onIncludeTerm(term) },
                        onExclude = { onExcludeTerm(term) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - rowTerms.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagActionCell(
    label: String,
    videoCount: Int?,
    includeSelected: Boolean,
    excludeSelected: Boolean,
    onTagLongPress: (() -> Unit)?,
    onInclude: () -> Unit,
    onExclude: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TagSelectionSurface(
            tag = label,
            active = includeSelected || excludeSelected,
            modifier = Modifier.fillMaxWidth(),
            longPressModifier = if (onTagLongPress != null) {
                Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onTagLongPress,
                )
            } else {
                Modifier
            },
        )
        // Reserve the count line so asynchronous metadata cannot resize rows mid-scroll.
        Text(
            text = videoCount?.toString().orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TagActionPill(
                label = "+",
                actionLabel = "Include $label in Search",
                selected = includeSelected,
                onClick = onInclude,
                modifier = Modifier.weight(1f),
            )
            TagActionPill(
                label = "-",
                actionLabel = "Exclude $label from Search",
                selected = excludeSelected,
                onClick = onExclude,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FavoriteTagActionCell(
    tag: String,
    videoCount: Int?,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
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
            text = videoCount?.toString().orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TagActionPill(
                label = "+",
                actionLabel = "Include $tag in Search",
                selected = false,
                onClick = onAdd,
                modifier = Modifier.weight(1f),
            )
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
    label: String? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                icon()
            } else if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal data class PostActionTermGroup(
    val label: String,
    val terms: List<SearchTerm>,
)

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
                POST_ACTION_FACET_ORDER.indexOf(entry.key.facet)
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

internal fun postActionTermLabel(term: SearchTerm): String {
    return searchTermChipLabel(term, excluded = false)
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

private val POST_ACTION_FACET_ORDER = listOf(
    SearchFacet.TAG,
    SearchFacet.ARTIST,
    SearchFacet.CHARACTER,
    SearchFacet.SERIES,
    SearchFacet.GROUP,
    SearchFacet.TYPE,
    SearchFacet.LANGUAGE,
)
private const val GENERAL_TAG_NAMESPACE = "tag"
