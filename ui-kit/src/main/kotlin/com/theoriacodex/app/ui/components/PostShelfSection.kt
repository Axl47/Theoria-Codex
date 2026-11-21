package com.theoriacodex.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ArrowDropDown
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
    failed: Boolean,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onLongPress: () -> Unit,
    icon: ImageVector? = null,
    countSuffix: String? = null,
    onMore: (() -> Unit)? = null,
    showSeeAll: Boolean = onMore != null,
    highlight: String? = null,
    titleAction: ShelfTitleAction? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                // A title with its own action leaves this row's merged text, so the row names the shelf it opens.
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress, role = Role.Button,
                    onClickLabel = titleAction?.let { "Open $title" }, onLongClickLabel = "Actions for $title")
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showSeeAll) {
                ManagedShelfHeading(title, listOfNotNull(count?.toString(), countSuffix).joinToString(" "), icon, highlight, onMore, titleAction)
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

/**
 * Makes a managed shelf's title its own control, apart from the heading that opens the shelf: For You's title
 * opens why the shelf was picked. [disclosure] adds a chevron where the title alone does not suggest more.
 */
data class ShelfTitleAction(val label: String, val disclosure: Boolean = false, val onClick: () -> Unit)

/**
 * A shelf heading that identifies its collection and shows where See all and its actions are; a
 * [highlight] such as "3 new" follows the [count] in the accent color.
 */
@Composable
private fun RowScope.ManagedShelfHeading(
    title: String,
    count: String,
    icon: ImageVector?,
    highlight: String?,
    onMore: (() -> Unit)?,
    titleAction: ShelfTitleAction?,
) {
    icon?.let { Icon(it, null, Modifier.padding(end = 8.dp), tint = MaterialTheme.colorScheme.primary) }
    Row(
        titleAction?.let { Modifier.clickable(onClickLabel = it.label, role = Role.Button, onClick = it.onClick) } ?: Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (titleAction?.disclosure == true) Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp))
    }
    Row(Modifier.padding(start = 8.dp).weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        highlight?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
    }
    Text("See all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    onMore?.let { IconButton(onClick = it) { Icon(Icons.Default.MoreVert, "Actions for $title") } }
}
