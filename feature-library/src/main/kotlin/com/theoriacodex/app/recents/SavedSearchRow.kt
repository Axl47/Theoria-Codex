package com.theoriacodex.app.recents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.components.newPostsLabel

/** One saved search: its name, where it searches, and Rename and Remove. */
@Composable
internal fun SavedSearchRow(
    entry: SavedSearchEntry,
    onOpen: (SavedSearchEntry) -> Unit,
    onRename: (SavedSearchEntry) -> Unit,
    onRemove: (SavedSearchEntry) -> Unit,
    selected: Boolean? = null,
    newCount: Int = 0,
) {
    val presentation = recentSearchPresentation(entry.search)
    val context = when (entry.search.kind) {
        RecentSearchKind.SOURCE -> entry.search.sources.single().displayName()
        RecentSearchKind.UNIFIED -> "Unified"
        RecentSearchKind.MULTI_SEARCH -> "Multi-Search"
        RecentSearchKind.FYP -> "For You"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            selected?.let { included ->
                androidx.compose.material3.Checkbox(
                    checked = included, onCheckedChange = { onOpen(entry) },
                    modifier = Modifier.semantics { contentDescription = "Include ${entry.name}" },
                )
            }
            Column(
                modifier = Modifier.weight(1f).clickable { onOpen(entry) }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall)
                    newPostsLabel(newCount)?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    "$context · ${presentation.title}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onRename(entry) }) {
                Icon(Icons.Default.Edit, contentDescription = "Rename ${entry.name}")
            }
            IconButton(onClick = { onRemove(entry) }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove ${entry.name}")
            }
        }
    }
}
