package com.theoriacodex.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.SourceIcon
import com.theoriacodex.app.source.SourceLetterIcon
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.isRule34Family
import com.theoriacodex.domain.model.SourceKey

/** Shared source dropdown; null selects the route's combined view. */
@Composable
internal fun SourceSelector(
    selectedSource: SourceKey?,
    availableSources: List<SourceKey>,
    allSourcesLabel: String,
    accessibilityLabel: String,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSourceSelected: (SourceKey?) -> Unit,
    minimumHeight: Dp = 48.dp,
    verticalPadding: Dp = 2.dp,
) {
    val selectedLabel = selectedSource?.displayName() ?: allSourcesLabel
    Box {
        Box(
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = minimumHeight)
                .semantics {
                    contentDescription = accessibilityLabel
                }
                .expandableControlSemantics(
                    expanded = expanded,
                    description = "${if (expanded) "Expanded" else "Collapsed"}; " +
                        "$selectedLabel selected",
                    onExpandedChange = onExpandedChange,
                )
                .clickable(
                    enabled = enabled,
                    role = Role.DropdownList,
                    onClick = { onExpandedChange(true) },
                )
                .padding(horizontal = 8.dp, vertical = verticalPadding),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.clearAndSetSemantics {}) {
                SourceScopeIndicator(selectedSource, availableSources.size)
            }
        }
        SourceIconMenu(
            expanded = expanded,
            onDismiss = { onExpandedChange(false) },
            options = (listOf(null) + availableSources) - selectedSource,
            highlightedSources = setOfNotNull(selectedSource),
            combinedLabel = allSourcesLabel,
            onSelect = onSourceSelected,
        )
    }
}

/** A single source's mark, or the combined view's mark with how many sources it spans. */
@Composable
internal fun SourceScopeIndicator(source: SourceKey?, combinedSourceCount: Int) {
    if (source != null) {
        SourceIcon(source)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        SourceLetterIcon(COMBINED_SOURCES_MARK)
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
            Text(
                text = combinedSourceCount.toString(),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Source choices as floating icon chips, with the Rule34 family folded into a labeled submenu.
 * A null option is the combined view; [onLongPress] enables a secondary action per source.
 */
@Composable
internal fun SourceIconMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<SourceKey?>,
    highlightedSources: Collection<SourceKey>,
    combinedLabel: String,
    onSelect: (SourceKey?) -> Unit,
    onLongPress: ((SourceKey) -> Unit)? = null,
) {
    var r34Expanded by remember(expanded) { mutableStateOf(false) }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = if (r34Expanded) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
        shadowElevation = if (r34Expanded) 8.dp else 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(if (r34Expanded) 0.dp else 6.dp)) {
            SourceMenuChoices(
                options, highlightedSources, combinedLabel, r34Expanded, { r34Expanded = it }, onSelect, onLongPress,
            )
        }
    }
}

@Composable
private fun SourceMenuChoices(
    options: List<SourceKey?>,
    highlightedSources: Collection<SourceKey>,
    combinedLabel: String,
    r34Expanded: Boolean,
    onR34ExpandedChange: (Boolean) -> Unit,
    onSelect: (SourceKey?) -> Unit,
    onLongPress: ((SourceKey) -> Unit)?,
) {
    val r34Options = options.filter { it?.isRule34Family() == true }
    if (r34Expanded) {
        IconButton(onClick = { onR34ExpandedChange(false) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to sources")
        }
    }
    (if (r34Expanded) r34Options else options - r34Options.toSet()).forEach { source ->
        SourceMenuOption(source, combinedLabel, source in highlightedSources, r34Expanded, onSelect, onLongPress)
    }
    if (!r34Expanded && r34Options.isNotEmpty()) {
        R34MenuChip(highlightedSources.any { it.isRule34Family() }) { onR34ExpandedChange(true) }
    }
}

@Composable
private fun R34MenuChip(selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 48.dp)
            .clip(CircleShape)
            .background(sourceChipColor(selected))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "R34"
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        SourceLetterIcon("R34")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceMenuOption(
    source: SourceKey?,
    combinedLabel: String,
    selected: Boolean,
    showTitle: Boolean,
    onSelect: (SourceKey?) -> Unit,
    onLongPress: ((SourceKey) -> Unit)?,
) {
    val label = source?.displayName() ?: combinedLabel
    val longPress = source?.let { key -> onLongPress?.let { { it(key) } } }
    Row(
        modifier = Modifier
            .sourceOptionShape(selected, showTitle)
            .combinedClickable(
                role = if (onLongPress == null) Role.Button else Role.Checkbox,
                onClickLabel = "Select $label",
                onLongClickLabel = longPress?.let { "Add or remove $label from Unified search" },
                onClick = { onSelect(source) },
                onLongClick = longPress,
            )
            .semantics {
                this.selected = selected
                contentDescription = label
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showTitle) {
            Text(label)
        } else {
            Box(Modifier.clearAndSetSemantics {}) {
                if (source == null) SourceLetterIcon(COMBINED_SOURCES_MARK) else SourceIcon(source)
            }
        }
    }
}

/** Icon chips float as capsules; the submenu's titled rows fill the width and tint only when selected. */
@Composable
private fun Modifier.sourceOptionShape(selected: Boolean, showTitle: Boolean): Modifier =
    if (showTitle) {
        fillMaxWidth().heightIn(min = 48.dp)
            .background(if (selected) sourceChipColor(true) else Color.Transparent)
    } else {
        size(width = 64.dp, height = 48.dp).clip(CircleShape).background(sourceChipColor(selected))
    }

@Composable
private fun sourceChipColor(selected: Boolean): Color =
    if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer

private const val COMBINED_SOURCES_MARK = "U"
