package com.theoriacodex.app.codex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.SourceIcon
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import com.theoriacodex.app.ui.components.TagGroupCard
import com.theoriacodex.app.ui.components.TagGroupOrLabel
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

internal data class CodexAutomaticTagGroup(
    val source: SourceKey,
    val groupIndex: Int,
    val rows: List<CodexAutomaticTagRow>,
)

internal data class CodexAutomaticTagPresentation(
    val automaticRows: List<CodexAutomaticTagRow>,
    val automaticGroups: List<CodexAutomaticTagGroup>,
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
        automaticGroups = automaticRows
            .groupBy { row -> row.automaticTag.source to row.automaticTag.groupIndex }
            .map { (identity, rows) ->
                CodexAutomaticTagGroup(identity.first, identity.second, rows)
            },
        availableSections = sections,
    )
}

internal fun representedAutomaticTagSources(
    tagOptionsBySource: Map<SourceKey, List<CodexSearchTagOption>>,
): List<SourceKey> = SourcePresentationCatalog.orderedPresentations()
    .map { presentation -> presentation.source }
    .filter { source -> tagOptionsBySource[source].orEmpty().isNotEmpty() }

/** Source tabs include sources with saved rules even after their posts leave the Codex. */
internal fun automaticTagSourceTabs(
    automaticTags: List<CodexAutomaticTag>,
    tagOptionsBySource: Map<SourceKey, List<CodexSearchTagOption>>,
): List<SourceKey> {
    val ruleSources = automaticTags.mapTo(mutableSetOf(), CodexAutomaticTag::source)
    return SourcePresentationCatalog.orderedPresentations()
        .map { presentation -> presentation.source }
        .filter { source -> source in ruleSources || tagOptionsBySource[source].orEmpty().isNotEmpty() }
}

/** The group a new requirement would occupy; indexes are never reused while a group exists. */
internal fun nextAutomaticTagGroupIndex(groups: List<CodexAutomaticTagGroup>): Int =
    (groups.maxOfOrNull(CodexAutomaticTagGroup::groupIndex) ?: -1) + 1

/** The picker stays open for a source without rules, otherwise only while a group is being edited. */
internal fun automaticTagAddTarget(
    requestedGroupIndex: Int?,
    groups: List<CodexAutomaticTagGroup>,
): Int? = requestedGroupIndex ?: if (groups.isEmpty()) 0 else null

internal fun filterAutomaticTagRows(
    rows: List<CodexAutomaticTagRow>,
    query: String,
): List<CodexAutomaticTagRow> {
    val normalizedQuery = query.normalizedTagFilterText()
    if (normalizedQuery.isBlank()) return rows
    return rows.filter { row ->
        normalizedQuery in row.automaticTag.tag.normalizedTagFilterText()
    }
}

/** Dedicated editor for the rules that route newly liked posts into [codexName]. */
@Composable
internal fun CodexAutoSaveSheet(
    codexName: String,
    automaticTags: List<CodexAutomaticTag>,
    tagOptionsBySource: Map<SourceKey, List<CodexSearchTagOption>>,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    onSetAutomaticTag: (CodexAutomaticTag, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ExpandedBottomSheet(onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Auto-save to $codexName",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                loading -> AutoSaveMessage("Loading collection tags…")
                failed -> TextButton(onClick = onRetry) { Text("Could not load tags. Retry") }
                else -> AutoSaveEditor(automaticTags, tagOptionsBySource, onSetAutomaticTag)
            }
        }
    }
}

@Composable
private fun AutoSaveEditor(
    automaticTags: List<CodexAutomaticTag>,
    tagOptionsBySource: Map<SourceKey, List<CodexSearchTagOption>>,
    onSetAutomaticTag: (CodexAutomaticTag, Boolean) -> Unit,
) {
    val presentation = codexAutomaticTagPresentation(automaticTags, tagOptionsBySource)
    val sources = automaticTagSourceTabs(automaticTags, tagOptionsBySource)
    var preferredSource by remember { mutableStateOf<SourceKey?>(null) }
    var requestedGroupIndex by remember { mutableStateOf<Int?>(null) }
    val selectedSource = preferredSource?.takeIf { source -> source in sources }
        ?: automaticTags.firstOrNull()?.source
        ?: sources.firstOrNull()
    if (selectedSource == null) {
        AutoSaveMessage("Save a post to this Codex to choose its tags.")
        return
    }
    val groups = presentation.automaticGroups.filter { group -> group.source == selectedSource }
    val addTarget = automaticTagAddTarget(requestedGroupIndex, groups)

    AutoSaveSourceTabs(sources, selectedSource, automaticTags) { source ->
        preferredSource = source
        requestedGroupIndex = null
    }
    AutoSaveRule(
        source = selectedSource,
        groups = groups,
        addTarget = addTarget,
        onAddTo = { groupIndex -> requestedGroupIndex = groupIndex },
        onRemove = { group, tag ->
            if (group.rows.size == 1) requestedGroupIndex = null
            onSetAutomaticTag(tag, false)
        },
    )
    if (addTarget != null) {
        HorizontalDivider()
        AutoSaveTagPicker(
            source = selectedSource,
            rows = presentation.availableSections.firstOrNull { it.source == selectedSource }?.rows.orEmpty(),
            canClose = groups.isNotEmpty(),
            onClose = { requestedGroupIndex = null },
            onPick = { tag -> onSetAutomaticTag(tag.copy(groupIndex = addTarget), true) },
        )
    }
}

@Composable
private fun AutoSaveSourceTabs(
    sources: List<SourceKey>,
    selected: SourceKey,
    automaticTags: List<CodexAutomaticTag>,
    onSelect: (SourceKey) -> Unit,
) {
    val ruleCounts = automaticTags.groupingBy(CodexAutomaticTag::source).eachCount()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sources.forEach { source ->
            AutoSaveSourceTab(source, source == selected, ruleCounts[source] ?: 0) { onSelect(source) }
        }
    }
}

@Composable
private fun AutoSaveSourceTab(source: SourceKey, active: Boolean, count: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (active) colors.primary.copy(alpha = 0.18f) else colors.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, if (active) colors.primary else colors.outlineVariant),
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "${source.displayName()}, $count ${if (count == 1) "tag" else "tags"}"
            role = Role.Tab
            selected = active
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceIcon(source)
            if (count > 0) Text(count.toString(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun AutoSaveRule(
    source: SourceKey,
    groups: List<CodexAutomaticTagGroup>,
    addTarget: Int?,
    onAddTo: (Int) -> Unit,
    onRemove: (CodexAutomaticTagGroup, CodexAutomaticTag) -> Unit,
) {
    Text(
        text = "Save a liked ${source.displayName()} post when it has",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    groups.forEachIndexed { index, group ->
        if (index > 0) AutoSaveAndLabel()
        TagGroupCard(
            active = addTarget == group.groupIndex,
            content = {
                group.rows.forEach { row ->
                    val tag = row.automaticTag
                    if (row != group.rows.first()) TagGroupOrLabel()
                    InputChip(
                        selected = false,
                        onClick = { onRemove(group, tag) },
                        label = { Text(tag.tag, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) },
                        modifier = Modifier.semantics { contentDescription = "Remove ${tag.tag} from Auto-save" },
                    )
                }
                if (addTarget != group.groupIndex) {
                    TextButton(onClick = { onAddTo(group.groupIndex) }) { Text("+ or") }
                }
            },
            hint = "Adding alternatives here".takeIf { addTarget == group.groupIndex },
        )
    }
    val draft = addTarget?.takeIf { target -> groups.none { group -> group.groupIndex == target } }
    if (draft != null) {
        if (groups.isNotEmpty()) AutoSaveAndLabel()
        TagGroupCard(active = true, hint = "Choose a tag below")
    } else if (groups.isNotEmpty()) {
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onAddTo(nextAutomaticTagGroupIndex(groups)) },
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Text("Add another requirement", Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun AutoSaveAndLabel() {
    Text(
        text = "AND",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun AutoSaveTagPicker(
    source: SourceKey,
    rows: List<CodexAutomaticTagRow>,
    canClose: Boolean,
    onClose: () -> Unit,
    onPick: (CodexAutomaticTag) -> Unit,
) {
    var filter by remember(source) { mutableStateOf("") }
    val visibleRows = filterAutomaticTagRows(rows, filter)
    val tags = visibleRows.map { row -> CodexSearchTagOption(row.automaticTag.tag, row.postCount) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AutomaticTagFilterField(filter, { filter = it }, Modifier.weight(1f))
        if (canClose) TextButton(onClick = onClose) { Text("Done") }
    }
    when {
        rows.isEmpty() -> AutoSaveMessage("No more ${source.displayName()} tags in this Codex")
        visibleRows.isEmpty() -> AutoSaveMessage("No tags match \"${filter.trim()}\"")
        else -> CodexSearchTagSelectionGrid(
            tags = tags,
            selectedTags = emptySet(),
            onToggleTag = { tag -> onPick(CodexAutomaticTag(source, tag)) },
        )
    }
}

@Composable
private fun AutoSaveMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun AutomaticTagFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text("Filter tags") },
        singleLine = true,
        leadingIcon = {
            Icon(imageVector = Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = value.takeIf(String::isNotEmpty)?.let {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Clear tag filter")
                }
            }
        },
    )
}

private fun String.normalizedTagFilterText(): String = trim()
    .lowercase()
    .replace('_', ' ')
    .replace(TAG_FILTER_WHITESPACE, " ")

private val TAG_FILTER_WHITESPACE = Regex("\\s+")
