package com.theoriacodex.app.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.ui.components.TagGroupCard
import com.theoriacodex.app.ui.components.TagGroupOrLabel
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup

internal data class SelectedTerm(val term: SearchTerm, val excluded: Boolean)

/** What a [ComposerQuery] needs from the composer that owns its selection and add target. */
internal class ComposerQueryActions(
    val savedTags: ComposerSavedTags,
    val onSelect: (SelectedTerm?) -> Unit,
    val onAddAlternative: (SearchTerm) -> Unit,
    /** Edits the draft and closes the tag's menu. */
    val onEdit: (SearchAction) -> Unit,
    /** Removes tags in a way the user can undo. */
    val onRemove: (SearchAction, removed: List<SearchTerm>, excluded: Boolean) -> Unit,
)

/**
 * The query as one readable expression: every group must match, in draft order, and a group with
 * several tags is a capsule of alternatives. Excluded tags follow. Tapping a tag opens its actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComposerQuery(
    terms: ComposerTerms,
    targetIndex: Int,
    selection: SelectedTerm?,
    actions: ComposerQueryActions,
) {
    if (terms.groups.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            terms.groups.forEachIndexed { index, group ->
                if (group.terms.size == 1 && index != targetIndex) {
                    ComposerRequiredTerm(group.terms.single(), index, group, selection, actions)
                } else {
                    ComposerAlternativesCapsule(index, group, index == targetIndex, selection, actions)
                }
            }
        }
    }
    if (terms.excluded.isNotEmpty()) {
        ComposerSectionTitle("Excluded")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            terms.excluded.forEach { term ->
                val selected = SelectedTerm(term, excluded = true)
                ComposerTermChip(term, selected, selected == selection, actions.onSelect) {
                    DropdownMenuItem(
                        text = { Text("Make required") },
                        onClick = {
                            actions.onEdit(SearchAction.RemoveExcludeTerm(term))
                            actions.onEdit(SearchAction.AddIncludeTerm(term))
                        },
                    )
                    ComposerSaveItem(listOf(term), "Save tag", actions)
                    ComposerRemoveItem("Remove") {
                        actions.onRemove(SearchAction.RemoveExcludeTerm(term), listOf(term), true)
                    }
                }
            }
        }
    }
}

/** A group of alternatives. As the add target it is highlighted and shows where the next tag lands. */
@Composable
private fun ComposerAlternativesCapsule(
    index: Int,
    group: SearchTermGroup,
    isTarget: Boolean,
    selection: SelectedTerm?,
    actions: ComposerQueryActions,
) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(isTarget) { if (isTarget) requester.bringIntoView() }
    TagGroupCard(
        active = isTarget,
        modifier = Modifier.bringIntoViewRequester(requester),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        group.terms.forEachIndexed { termIndex, term ->
            if (termIndex > 0) TagGroupOrLabel()
            ComposerRequiredTerm(term, index, group, selection, actions)
        }
        if (isTarget) {
            TagGroupOrLabel()
            Text(
                "type below",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(onClick = { actions.onAddAlternative(group.terms.first()) }) { Text("+ or") }
        }
    }
}

@Composable
private fun ComposerRequiredTerm(
    term: SearchTerm,
    groupIndex: Int,
    group: SearchTermGroup,
    selection: SelectedTerm?,
    actions: ComposerQueryActions,
) {
    val selected = SelectedTerm(term, excluded = false)
    val inAlternatives = group.terms.size > 1
    ComposerTermChip(term, selected, selected == selection, actions.onSelect) {
        DropdownMenuItem(text = { Text("Add an alternative") }, onClick = { actions.onAddAlternative(term) })
        if (inAlternatives) {
            DropdownMenuItem(
                text = { Text("Make required on its own") },
                onClick = { actions.onEdit(SearchAction.SplitIncludeAlternative(groupIndex, term)) },
            )
        }
        ComposerSaveItem(listOf(term), "Save tag", actions)
        if (inAlternatives) ComposerSaveItem(group.terms, "Save the “or” group", actions)
        DropdownMenuItem(
            text = { Text("Exclude instead") },
            onClick = {
                actions.onEdit(SearchAction.RemoveIncludeTerm(term))
                actions.onEdit(SearchAction.AddExcludeTerm(term))
            },
        )
        ComposerRemoveItem("Remove") { actions.onRemove(SearchAction.RemoveIncludeTerm(term), listOf(term), false) }
        if (inAlternatives) {
            ComposerRemoveItem("Remove the whole “or” group") {
                actions.onRemove(SearchAction.RemoveIncludeGroup(groupIndex), group.terms, false)
            }
        }
    }
}

/**
 * Saves [terms] as one saved tag, or as a saved "or" group when there are several, or offers to
 * remove the entry already saved for exactly them. Typed tags cannot be saved, so they show nothing.
 */
@Composable
private fun ComposerSaveItem(terms: List<SearchTerm>, saveLabel: String, actions: ComposerQueryActions) {
    val saved = actions.savedTags
    val entry = saved.entryFor(terms)
    val source = saved.saveSource
    when {
        entry != null -> DropdownMenuItem(
            text = { Text(if (terms.size > 1) "Remove the “or” group from saved tags" else "Remove from saved tags") },
            onClick = {
                saved.onRemove(entry.source, entry.tags)
                actions.onSelect(null)
            },
        )
        source != null && saved.canSave(terms) -> DropdownMenuItem(
            text = { Text(saveLabel) },
            onClick = {
                saved.onSave(source, terms.map(SearchTerm::value))
                actions.onSelect(null)
            },
        )
    }
}

@Composable
private fun ComposerRemoveItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label, color = MaterialTheme.colorScheme.error) }, onClick = onClick)
}

/** One tag. Its type is a quiet prefix, and its actions open beside it while it is selected. */
@Composable
private fun ComposerTermChip(
    term: SearchTerm,
    self: SelectedTerm,
    isSelected: Boolean,
    onSelect: (SelectedTerm?) -> Unit,
    menuItems: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val label = buildAnnotatedString {
        if (self.excluded) append("− ")
        searchTermTypeLabel(term)?.let { type ->
            withStyle(SpanStyle(color = colors.onSurfaceVariant)) { append(type) }
            append(" ")
        }
        append(term.value)
    }
    Box {
        InputChip(
            selected = isSelected,
            onClick = { onSelect(self.takeUnless { isSelected }) },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = if (self.excluded) {
                InputChipDefaults.inputChipColors(
                    labelColor = colors.error,
                    selectedLabelColor = colors.error,
                    selectedContainerColor = colors.error.copy(alpha = 0.14f),
                )
            } else {
                InputChipDefaults.inputChipColors()
            },
        )
        DropdownMenu(expanded = isSelected, onDismissRequest = { onSelect(null) }) {
            Text(
                term.value,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            menuItems()
        }
    }
}

@Composable
internal fun ComposerSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp),
    )
}
