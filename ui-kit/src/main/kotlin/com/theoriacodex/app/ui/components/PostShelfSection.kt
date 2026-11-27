package com.theoriacodex.app.ui.components

import androidx.compose.foundation.layout.RowScope
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
fun PostShelfSection(
    title: String,
    count: Int?,
    posts: List<Post>,
    loading: Boolean,
    failure: ShelfFailure?,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onLongPress: () -> Unit,
    icon: ImageVector? = null,
    countSuffix: String? = null,
    onMore: (() -> Unit)? = null,
    highlight: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress, role = Role.Button,
                    onLongClickLabel = "Actions for $title")
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onMore != null) {
                ManagedShelfHeading(title, listOfNotNull(count?.toString(), countSuffix).joinToString(" "), icon, highlight, onMore)
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
            failure == null -> Text("No posts", Modifier.padding(horizontal = 16.dp))
        }
        if (failure != null) ShelfRetry(failure.takeIf { posts.isNotEmpty() } ?: ShelfFailure.Load, onRetry)
    }
}

enum class ShelfFailure { Load, Refresh }

@Composable
private fun ShelfRetry(failure: ShelfFailure, onRetry: () -> Unit) {
    TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(if (failure == ShelfFailure.Refresh) "Couldn't refresh. Retry" else "Could not load some posts. Retry")
    }
}

@Composable
private fun RowScope.ManagedShelfHeading(
    title: String,
    count: String,
    icon: ImageVector?,
    highlight: String?,
    onMore: (() -> Unit)?,
) {
    icon?.let { Icon(it, null, Modifier.padding(end = 8.dp), tint = MaterialTheme.colorScheme.primary) }
    Text(title, style = MaterialTheme.typography.titleMedium)
    Row(Modifier.padding(start = 8.dp).weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        highlight?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
    }
    Text("See all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    onMore?.let { IconButton(onClick = it) { Icon(Icons.Default.MoreVert, "Actions for $title") } }
}
