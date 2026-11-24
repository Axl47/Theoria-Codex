package com.theoriacodex.app.recents

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.theoriacodex.app.ui.components.ActionMenuRow
import com.theoriacodex.app.ui.components.ActionMenuSheet
import com.theoriacodex.app.ui.components.InlineNameField
import com.theoriacodex.data.repository.MAX_SAVED_SEARCH_NAME_LENGTH
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.savedFor

class RecentSearchSaves(
    val saved: List<SavedSearchEntry>,
    val justSavedId: String?,
    val save: (name: String, RecentSearchEntry) -> Unit,
    val rename: (id: String, name: String) -> Unit,
)

internal class RecentSearchSaving(
    private val saves: RecentSearchSaves,
    private val savedByHash: Map<String, List<SavedSearchEntry>>,
    val renamingId: String?,
    val startRename: (SavedSearchEntry) -> Unit,
    private val endRename: () -> Unit,
) {
    val justSavedId: String? get() = saves.justSavedId

    fun savedFor(entry: RecentSearchEntry): SavedSearchEntry? = savedByHash[entry.queryHash]?.savedFor(entry)

    fun bookmark(entry: RecentSearchEntry) {
        val saved = savedFor(entry)
        if (saved == null) saves.save(savedSearchName(entry), entry) else startRename(saved)
    }

    fun finishRename(saved: SavedSearchEntry, name: String?) {
        endRename()
        name?.let { saves.rename(saved.id, it) }
    }
}

@Composable
internal fun rememberRecentSearchSaving(saves: RecentSearchSaves?): RecentSearchSaving? {
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    val saved = saves?.saved.orEmpty()
    val savedByHash = remember(saved) { saved.groupBy { it.search.queryHash } }
    return saves?.let { RecentSearchSaving(it, savedByHash, renamingId, { entry -> renamingId = entry.id }, { renamingId = null }) }
}

internal fun RecentSearchSaving?.renaming(entry: RecentSearchEntry): SavedSearchEntry? =
    this?.savedFor(entry)?.takeIf { it.id == renamingId }

@Composable
internal fun SavedSearchNameField(saved: SavedSearchEntry, saving: RecentSearchSaving, style: TextStyle, modifier: Modifier) {
    InlineNameField(saved.name, style, modifier) { name -> saving.finishRename(saved, name) }
}

@Composable
internal fun SaveSearchControl(entry: RecentSearchEntry, saving: RecentSearchSaving) {
    val colors = MaterialTheme.colorScheme
    val saved = saving.savedFor(entry)
    when {
        saved == null -> IconButton(onClick = { saving.bookmark(entry) }) {
            Icon(Icons.Default.BookmarkBorder, "Save search", tint = colors.primary)
        }
        saved.id == saving.justSavedId -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Saved", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            TextButton(onClick = { saving.startRename(saved) }) { Text("Rename") }
        }
        else -> IconButton(onClick = { saving.startRename(saved) }) {
            Icon(Icons.Default.Bookmark, "Rename ${saved.name}", tint = colors.primary)
        }
    }
}

@Composable
internal fun RecentSearchMenu(
    entry: RecentSearchEntry,
    saving: RecentSearchSaving?,
    onRemove: ((RecentSearchEntry) -> Unit)?,
    onDismiss: () -> Unit,
) {
    ActionMenuSheet(onDismiss) {
        saving?.let { save ->
            val saved = save.savedFor(entry) != null
            ActionMenuRow(
                if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                if (saved) "Rename saved search" else "Save search",
            ) {
                onDismiss()
                save.bookmark(entry)
            }
        }
        onRemove?.let { remove ->
            ActionMenuRow(Icons.Default.Delete, REMOVE_FROM_HISTORY, tint = MaterialTheme.colorScheme.error) {
                onDismiss()
                remove(entry)
            }
        }
    }
}

private fun savedSearchName(entry: RecentSearchEntry) = recentSearchPresentation(entry).title.take(MAX_SAVED_SEARCH_NAME_LENGTH)
