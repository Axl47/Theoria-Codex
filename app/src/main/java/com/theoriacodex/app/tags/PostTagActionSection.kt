package com.theoriacodex.app.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    onAddIncludeTerm: (SearchTerm) -> Boolean,
    onAddExcludeTerm: (SearchTerm) -> Boolean,
    onRemoveIncludeTerm: (SearchTerm) -> Unit,
    onRemoveExcludeTerm: (SearchTerm) -> Unit,
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)? = null,
) {
    Text("Search terms", style = MaterialTheme.typography.titleSmall)
    val termGroups = remember(post.taxonomy, post.canonicalTags, post.rawTags) {
        postActionTermGroups(post)
    }
    val terms = remember(termGroups) { termGroups.flatMap(PostActionTermGroup::terms) }
    val generalTags = remember(terms) {
        terms.filter(SearchTerm::isGeneralPostTag).map(SearchTerm::value).distinct()
    }
    var tagSelections by remember(post.id.source, post.id.sourcePostId) {
        mutableStateOf<Map<TagActionSelection, Set<SearchTerm>>>(emptyMap())
    }
    var tagVideoCounts by remember(post.id.source, generalTags) {
        mutableStateOf(
            generalTags.associateWith { tag ->
                tagVideoCountProvider(post.id.source, tag)
            }
        )
    }
    LaunchedEffect(post.id.source, generalTags) {
        val missingTags = generalTags.filter { tag -> tagVideoCounts[tag] == null }
        if (missingTags.isEmpty()) return@LaunchedEffect
        val fetchedCounts = fetchTagVideoCounts(post.id.source, missingTags)
        if (fetchedCounts.isNotEmpty()) {
            tagVideoCounts = tagVideoCounts + fetchedCounts
        }
    }

    TagActionGrid(
        groups = termGroups,
        videoCounts = tagVideoCounts,
        includedTerms = tagSelections[TagActionSelection.INCLUDE].orEmpty(),
        excludedTerms = tagSelections[TagActionSelection.EXCLUDE].orEmpty(),
        onFavoriteTagLongPress = if (onFavoriteTagLongPress != null) {
            { tag -> onFavoriteTagLongPress(post.id.source, tag) }
        } else {
            null
        },
        onIncludeTerm = { term ->
            val included = tagSelections[TagActionSelection.INCLUDE].orEmpty()
            val excluded = tagSelections[TagActionSelection.EXCLUDE].orEmpty()
            if (term in included) {
                onRemoveIncludeTerm(term)
                tagSelections = tagSelections.removeSelectedTerm(TagActionSelection.INCLUDE, term)
            } else {
                val accepted = onAddIncludeTerm(term)
                if (accepted && term in excluded) onRemoveExcludeTerm(term)
                tagSelections = tagSelections.afterSelectionAttempt(
                    target = TagActionSelection.INCLUDE,
                    term = term,
                    accepted = accepted,
                )
            }
        },
        onExcludeTerm = { term ->
            val included = tagSelections[TagActionSelection.INCLUDE].orEmpty()
            val excluded = tagSelections[TagActionSelection.EXCLUDE].orEmpty()
            if (term in excluded) {
                onRemoveExcludeTerm(term)
                tagSelections = tagSelections.removeSelectedTerm(TagActionSelection.EXCLUDE, term)
            } else {
                val accepted = onAddExcludeTerm(term)
                if (accepted && term in included) onRemoveIncludeTerm(term)
                tagSelections = tagSelections.afterSelectionAttempt(
                    target = TagActionSelection.EXCLUDE,
                    term = term,
                    accepted = accepted,
                )
            }
        },
    )
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
    var tagVideoCounts by remember(source, tags) {
        mutableStateOf(
            tags.associateWith { tag ->
                tagVideoCountProvider(source, tag)
            }
        )
    }
    LaunchedEffect(source, tags) {
        val missingTags = tags.filter { tag -> tagVideoCounts[tag] == null }
        if (missingTags.isEmpty()) return@LaunchedEffect
        val fetchedCounts = fetchTagVideoCounts(source, missingTags)
        if (fetchedCounts.isNotEmpty()) {
            tagVideoCounts = tagVideoCounts + fetchedCounts
        }
    }

    if (tags.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    tags.chunked(3).forEach { rowTags ->
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
            repeat(3 - rowTags.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

internal enum class TagActionSelection {
    INCLUDE,
    EXCLUDE,
}

internal fun Map<TagActionSelection, Set<SearchTerm>>.afterSelectionAttempt(
    target: TagActionSelection,
    term: SearchTerm,
    accepted: Boolean,
): Map<TagActionSelection, Set<SearchTerm>> {
    if (!accepted) return this
    val opposite = target.opposite()
    return this +
        (opposite to (get(opposite).orEmpty() - term)) +
        (target to (get(target).orEmpty() + term))
}

internal fun Map<TagActionSelection, Set<SearchTerm>>.removeSelectedTerm(
    target: TagActionSelection,
    term: SearchTerm,
): Map<TagActionSelection, Set<SearchTerm>> {
    return this + (target to (get(target).orEmpty() - term))
}

private fun TagActionSelection.opposite(): TagActionSelection {
    return when (this) {
        TagActionSelection.INCLUDE -> TagActionSelection.EXCLUDE
        TagActionSelection.EXCLUDE -> TagActionSelection.INCLUDE
    }
}

@Composable
private fun TagActionGrid(
    groups: List<PostActionTermGroup>,
    videoCounts: Map<String, Int?>,
    includedTerms: Set<SearchTerm>,
    excludedTerms: Set<SearchTerm>,
    onFavoriteTagLongPress: ((String) -> Unit)?,
    onIncludeTerm: (SearchTerm) -> Unit,
    onExcludeTerm: (SearchTerm) -> Unit,
) {
    if (groups.isEmpty()) {
        Text(
            text = "No search terms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    groups.forEach { group ->
        Text(
            text = group.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        group.terms.chunked(3).forEach { rowTerms ->
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
                        includeSelected = term in includedTerms,
                        excludeSelected = term in excludedTerms,
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
                repeat(3 - rowTerms.size) {
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
        if (videoCount != null) {
            Text(
                text = videoCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TagActionPill(
                label = "+",
                selected = includeSelected,
                onClick = onInclude,
                modifier = Modifier.weight(1f),
            )
            TagActionPill(
                label = "-",
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
        if (videoCount != null) {
            Text(
                text = videoCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TagActionPill(
                label = "+",
                selected = false,
                onClick = onAdd,
                modifier = Modifier.weight(1f),
            )
            TagActionPill(
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
    label: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.clickable(onClick = onClick),
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
