package com.theoriacodex.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * A section title with an optional count that folds its content away; [what] names it for TalkBack ("Show tags").
 * [trailing] sits before the chevron and keeps its own touch target.
 */
@Composable
fun FoldHeader(
    title: String,
    what: String,
    count: String?,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .sizeIn(minHeight = 48.dp)
                .clickable(role = Role.Button, onClickLabel = if (collapsed) "Show $what" else "Hide $what", onClick = onToggle)
                .semantics(mergeDescendants = true) { stateDescription = if (collapsed) "Collapsed" else "Expanded" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            count?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        trailing?.invoke()
        Box(
            Modifier.size(48.dp).clickable(onClick = onToggle).clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess, contentDescription = null)
        }
    }
}
