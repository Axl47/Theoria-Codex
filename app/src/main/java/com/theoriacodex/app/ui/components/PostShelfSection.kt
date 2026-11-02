package com.theoriacodex.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.Post

@Composable
internal fun PostShelfSection(
    title: String,
    count: Int?,
    posts: List<Post>,
    loading: Boolean,
    failed: Boolean,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onLongPress: () -> Unit,
    icon: ImageVector? = null,
    countSuffix: String? = null,
    onMore: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress,
                    role = Role.Button, onLongClickLabel = "Actions for $title")
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onMore != null) {
                ManagedShelfHeading(title, count, icon, countSuffix, onMore)
            } else {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (count != null) Text(count.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        when {
            posts.isNotEmpty() -> content()
            loading -> Text("Loading posts…", Modifier.padding(horizontal = 16.dp))
            !failed -> Text("No posts in this view yet", Modifier.padding(horizontal = 16.dp))
        }
        if (failed) TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Could not load some posts. Retry")
        }
    }
}

/** A shelf heading that identifies its collection and shows where See all and its actions are. */
@Composable
private fun RowScope.ManagedShelfHeading(
    title: String,
    count: Int?,
    icon: ImageVector?,
    countSuffix: String?,
    onMore: () -> Unit,
) {
    icon?.let { Icon(it, null, Modifier.padding(end = 8.dp), tint = MaterialTheme.colorScheme.primary) }
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (count != null) {
        Text(
            listOfNotNull(count.toString(), countSuffix).joinToString(" "),
            Modifier.padding(start = 8.dp).weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    } else {
        Spacer(Modifier.weight(1f))
    }
    Text("See all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "Actions for $title") }
}
