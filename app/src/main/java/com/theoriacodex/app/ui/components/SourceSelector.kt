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
import androidx.compose.foundation.layout.offset
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.app.source.LocalSourceHealth
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Public
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
import com.theoriacodex.app.source.SourcePresentationGroup
import com.theoriacodex.app.source.presentation
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
 * Source choices as floating icon chips. Groups with a folder label (Rule34, the Danbooru sites) fold
 * into one labeled chip that opens their titled submenu. A null option is the combined view;
 * [onLongPress] enables a secondary action per source.
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
    var openFolder by remember(expanded) { mutableStateOf<SourcePresentationGroup?>(null) }
    val folderOpen = openFolder != null
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = if (folderOpen) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
        shadowElevation = if (folderOpen) 8.dp else 0.dp,
        tonalElevation = 0.dp,
    ) {
        val health = LocalSourceHealth.current
        // A down source is retried when picked; a challenged one opens its verification instead.
        val pick: (SourceKey?) -> Unit = { source ->
            if (source != null && health.onPick(source)) onDismiss() else onSelect(source)
        }
        Column(verticalArrangement = Arrangement.spacedBy(if (folderOpen) 0.dp else 6.dp)) {
            SourceMenuChoices(
                options, highlightedSources, combinedLabel, openFolder, { openFolder = it }, pick, onLongPress, health.issues,
            )
        }
    }
}

@Composable
private fun SourceMenuChoices(
    options: List<SourceKey?>,
    highlightedSources: Collection<SourceKey>,
    combinedLabel: String,
    openFolder: SourcePresentationGroup?,
    onOpenFolder: (SourcePresentationGroup?) -> Unit,
    onSelect: (SourceKey?) -> Unit,
    onLongPress: ((SourceKey) -> Unit)?,
    issues: Map<SourceKey, HostOutageKind>,
) {
    // Options arrive in presentation order, so folders follow their first source's position.
    val folders = options.filterNotNull().groupBy { it.presentation().group }.filterKeys { it.folderLabel != null }
    if (openFolder != null) {
        IconButton(onClick = { onOpenFolder(null) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to sources")
        }
        folders[openFolder].orEmpty().forEach { source ->
            SourceMenuOption(source, combinedLabel, source in highlightedSources, true, onSelect, onLongPress, issues[source])
        }
        return
    }
    val folded = folders.values.flatten().toSet()
    (options - folded).forEach { source ->
        SourceMenuOption(source, combinedLabel, source in highlightedSources, false, onSelect, onLongPress, source?.let(issues::get))
    }
    folders.keys.forEach { group ->
        val label = requireNotNull(group.folderLabel)
        SourceFolderChip(label, group.folderIcon, highlightedSources.any { it.presentation().group == group }) { onOpenFolder(group) }
    }
}

@Composable
private fun SourceFolderChip(label: String, icon: ImageVector?, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 48.dp)
            .clip(CircleShape)
            .background(sourceChipColor(selected))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Color.White) else SourceLetterIcon(label)
    }
}

/** The small red mark on a source that is currently down; folders don't carry it. */
@Composable
private fun DownDot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(top = 8.dp, end = 12.dp)
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error),
    )
}

/** How TalkBack and the titled submenu describe a down source. */
private fun HostOutageKind.shortLabel(): String = when (this) {
    HostOutageKind.MAINTENANCE -> "down"
    HostOutageKind.CHALLENGE -> "needs browser verification"
    HostOutageKind.UNRESOLVED -> "can't be found"
}

/** Folders whose name doesn't fit as a letter mark; Custom matches the Sites row in Settings. */
private val SourcePresentationGroup.folderIcon: ImageVector?
    get() = if (this == SourcePresentationGroup.CUSTOM) Icons.Default.Public else null

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceMenuOption(
    source: SourceKey?,
    combinedLabel: String,
    selected: Boolean,
    showTitle: Boolean,
    onSelect: (SourceKey?) -> Unit,
    onLongPress: ((SourceKey) -> Unit)?,
    issue: HostOutageKind?,
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
                contentDescription = issue?.let { "$label, ${it.shortLabel()}" } ?: label
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showTitle) {
            Text(label)
            issue?.let {
                Text(
                    " · ${it.shortLabel()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Box(Modifier.clearAndSetSemantics {}) {
                if (source == null) SourceLetterIcon(COMBINED_SOURCES_MARK) else SourceIcon(source)
                if (issue != null) DownDot(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp))
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
