package com.theoriacodex.app.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey

@Composable
fun PostTagActionSection(
    post: Post,
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    onAddIncludeTag: (String) -> Unit,
    onAddExcludeTag: (String) -> Unit,
    onRemoveIncludeTag: (String) -> Unit,
    onRemoveExcludeTag: (String) -> Unit,
) {
    Text("Tags", style = MaterialTheme.typography.titleSmall)
    val distinctTags = remember(post.canonicalTags, post.rawTags) {
        interactiveTags(post)
    }
    var tagSelections by remember(post.id.source, post.id.sourcePostId) {
        mutableStateOf<Map<TagActionSelection, Set<String>>>(emptyMap())
    }
    var tagVideoCounts by remember(post.id.source, distinctTags) {
        mutableStateOf(
            distinctTags.associateWith { tag ->
                tagVideoCountProvider(post.id.source, tag)
            }
        )
    }
    LaunchedEffect(post.id.source, distinctTags) {
        val missingTags = distinctTags.filter { tag -> tagVideoCounts[tag] == null }
        if (missingTags.isEmpty()) return@LaunchedEffect
        val fetchedCounts = fetchTagVideoCounts(post.id.source, missingTags)
        if (fetchedCounts.isNotEmpty()) {
            tagVideoCounts = tagVideoCounts + fetchedCounts
        }
    }

    TagActionGrid(
        tags = distinctTags,
        videoCounts = tagVideoCounts,
        includedTags = tagSelections[TagActionSelection.INCLUDE].orEmpty(),
        excludedTags = tagSelections[TagActionSelection.EXCLUDE].orEmpty(),
        onIncludeTag = { tag ->
            val included = tagSelections[TagActionSelection.INCLUDE].orEmpty()
            val excluded = tagSelections[TagActionSelection.EXCLUDE].orEmpty()
            when {
                tag in included -> {
                    onRemoveIncludeTag(tag)
                    tagSelections = tagSelections + (TagActionSelection.INCLUDE to (included - tag))
                }
                tag in excluded -> {
                    onRemoveExcludeTag(tag)
                    onAddIncludeTag(tag)
                    tagSelections = tagSelections +
                        (TagActionSelection.EXCLUDE to (excluded - tag)) +
                        (TagActionSelection.INCLUDE to (included + tag))
                }
                else -> {
                    onAddIncludeTag(tag)
                    tagSelections = tagSelections + (TagActionSelection.INCLUDE to (included + tag))
                }
            }
        },
        onExcludeTag = { tag ->
            val included = tagSelections[TagActionSelection.INCLUDE].orEmpty()
            val excluded = tagSelections[TagActionSelection.EXCLUDE].orEmpty()
            when {
                tag in excluded -> {
                    onRemoveExcludeTag(tag)
                    tagSelections = tagSelections + (TagActionSelection.EXCLUDE to (excluded - tag))
                }
                tag in included -> {
                    onRemoveIncludeTag(tag)
                    onAddExcludeTag(tag)
                    tagSelections = tagSelections +
                        (TagActionSelection.INCLUDE to (included - tag)) +
                        (TagActionSelection.EXCLUDE to (excluded + tag))
                }
                else -> {
                    onAddExcludeTag(tag)
                    tagSelections = tagSelections + (TagActionSelection.EXCLUDE to (excluded + tag))
                }
            }
        },
    )
}

private enum class TagActionSelection {
    INCLUDE,
    EXCLUDE,
}

@Composable
private fun TagActionGrid(
    tags: List<String>,
    videoCounts: Map<String, Int?>,
    includedTags: Set<String>,
    excludedTags: Set<String>,
    onIncludeTag: (String) -> Unit,
    onExcludeTag: (String) -> Unit,
) {
    if (tags.isEmpty()) {
        Text(
            text = "No tags",
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
                TagActionCell(
                    tag = tag,
                    videoCount = videoCounts[tag],
                    includeSelected = tag in includedTags,
                    excludeSelected = tag in excludedTags,
                    onInclude = { onIncludeTag(tag) },
                    onExclude = { onExcludeTag(tag) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(3 - rowTags.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TagActionCell(
    tag: String,
    videoCount: Int?,
    includeSelected: Boolean,
    excludeSelected: Boolean,
    onInclude: () -> Unit,
    onExclude: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(999.dp),
            color = if (!includeSelected && !excludeSelected) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            } else {
                accent.copy(alpha = 0.16f)
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
private fun TagActionPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun interactiveTags(post: Post): List<String> {
    val canonical = post.canonicalTags
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    if (canonical.isNotEmpty()) {
        return canonical
    }
    return post.rawTags
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}
