package com.theoriacodex.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.displayName
import com.theoriacodex.domain.model.SourceKey

/** Shared source dropdown; null selects the route's combined view. */
@Composable
internal fun SourceSelector(
    selectedSource: SourceKey?,
    availableSources: List<SourceKey>,
    allSourcesLabel: String,
    accessibilityLabel: String,
    iconTestTag: String,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSourceSelected: (SourceKey?) -> Unit,
    minimumHeight: Dp = 48.dp,
    verticalPadding: Dp = 2.dp,
) {
    val selectedLabel = selectedSource?.displayName() ?: allSourcesLabel
    Box {
        Row(
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
                .padding(vertical = verticalPadding),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .testTag(iconTestTag),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            DropdownMenuItem(
                text = { Text(allSourcesLabel) },
                onClick = { onSourceSelected(null) },
            )
            availableSources.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.displayName()) },
                    onClick = { onSourceSelected(source) },
                )
            }
        }
    }
}
