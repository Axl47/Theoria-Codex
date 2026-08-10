package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.SourcePresentationCatalog
import com.theoriacodex.app.source.displayName
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagKey

internal data class CodexAutomaticTagRow(
    val automaticTag: CodexAutomaticTag,
    val postCount: Int,
)

internal data class CodexAutomaticTagSection(
    val source: SourceKey,
    val rows: List<CodexAutomaticTagRow>,
)

internal data class CodexAutomaticTagPresentation(
    val automaticRows: List<CodexAutomaticTagRow>,
    val availableSections: List<CodexAutomaticTagSection>,
)

internal fun codexAutomaticTagPresentation(
    automaticTags: List<CodexAutomaticTag>,
    tagOptionsBySource: Map<SourceKey, List<CodexSearchTagOption>>,
): CodexAutomaticTagPresentation {
    val automaticKeys = automaticTags.associateBy { tag ->
        tag.source to sourceTagKey(tag.source, tag.tag)
    }
    val countsByKey = tagOptionsBySource.flatMap { (source, options) ->
        options.map { option -> (source to sourceTagKey(source, option.tag)) to option.count }
    }.toMap()
    val automaticRows = automaticTags.map { tag ->
        CodexAutomaticTagRow(
            automaticTag = tag,
            postCount = countsByKey[tag.source to sourceTagKey(tag.source, tag.tag)] ?: 0,
        )
    }
    val sections = SourcePresentationCatalog.orderedPresentations().mapNotNull { presentation ->
        val source = presentation.source
        val rows = tagOptionsBySource[source]
            .orEmpty()
            .mapNotNull { option ->
                val automaticTag = CodexAutomaticTag(source = source, tag = option.tag)
                val key = source to sourceTagKey(source, option.tag)
                if (key in automaticKeys) null else CodexAutomaticTagRow(automaticTag, option.count)
            }
        rows.takeIf(List<*>::isNotEmpty)?.let { values ->
            CodexAutomaticTagSection(source = source, rows = values)
        }
    }
    return CodexAutomaticTagPresentation(
        automaticRows = automaticRows,
        availableSections = sections,
    )
}

@Composable
internal fun CodexAutomaticTagContent(
    isLikesCodex: Boolean,
    automaticTags: List<CodexAutomaticTag>,
    tagOptionsBySource: Map<SourceKey, List<CodexSearchTagOption>>,
    onSetAutomaticTag: (CodexAutomaticTag, Boolean) -> Unit,
) {
    if (isLikesCodex) {
        Text(
            text = "Every liked post is already added here automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    } else {
        CodexAutomaticTagSections(
            automaticTags = automaticTags,
            tagOptionsBySource = tagOptionsBySource,
            onSetAutomaticTag = onSetAutomaticTag,
        )
    }
}

@Composable
internal fun CodexAutomaticTagSections(
    automaticTags: List<CodexAutomaticTag>,
    tagOptionsBySource: Map<SourceKey, List<CodexSearchTagOption>>,
    onSetAutomaticTag: (CodexAutomaticTag, Boolean) -> Unit,
) {
    val presentation = codexAutomaticTagPresentation(automaticTags, tagOptionsBySource)
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        automaticTagItems(presentation.automaticRows, onSetAutomaticTag)
        availableTagItems(
            sections = presentation.availableSections,
            hasRepresentedTags = tagOptionsBySource.isNotEmpty(),
            onSetAutomaticTag = onSetAutomaticTag,
        )
    }
}

private fun LazyListScope.automaticTagItems(
    rows: List<CodexAutomaticTagRow>,
    onSetAutomaticTag: (CodexAutomaticTag, Boolean) -> Unit,
) {
    item {
        Text(
            text = "Automatic",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (rows.isEmpty()) {
        item {
            Text(
                text = "No automatic tags",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    } else {
        items(
            items = rows,
            key = { row ->
                val tag = row.automaticTag
                "automatic:${tag.source}:${sourceTagKey(tag.source, tag.tag)}"
            },
        ) { row ->
            CodexAutomaticTagRow(
                row = row,
                actionDescription = "Remove ${row.automaticTag.tag} from Automatic",
                actionIcon = Icons.Default.Close,
                onAction = { onSetAutomaticTag(row.automaticTag, false) },
            )
        }
    }
}

private fun LazyListScope.availableTagItems(
    sections: List<CodexAutomaticTagSection>,
    hasRepresentedTags: Boolean,
    onSetAutomaticTag: (CodexAutomaticTag, Boolean) -> Unit,
) {
    item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
    item { Text(text = "Tags", style = MaterialTheme.typography.titleSmall) }
    if (sections.isEmpty()) {
        item {
            Text(
                text = if (hasRepresentedTags) "All tags are automatic" else "No tags in this Codex",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    } else {
        sections.forEach { section ->
            item(key = "source:${section.source}") {
                Text(
                    text = section.source.displayName(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(
                items = section.rows,
                key = { row ->
                    val tag = row.automaticTag
                    "available:${tag.source}:${sourceTagKey(tag.source, tag.tag)}"
                },
            ) { row ->
                CodexAutomaticTagRow(
                    row = row,
                    actionDescription = "Add ${row.automaticTag.tag} to Automatic",
                    actionIcon = Icons.Default.Add,
                    onAction = { onSetAutomaticTag(row.automaticTag, true) },
                )
            }
        }
    }
}

@Composable
private fun CodexAutomaticTagRow(
    row: CodexAutomaticTagRow,
    actionDescription: String,
    actionIcon: ImageVector,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.automaticTag.tag, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = buildString {
                    append(row.automaticTag.source.displayName())
                    append(" · ")
                    append(row.postCount)
                    append(if (row.postCount == 1) " post" else " posts")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onAction) {
            Icon(imageVector = actionIcon, contentDescription = actionDescription)
        }
    }
}
