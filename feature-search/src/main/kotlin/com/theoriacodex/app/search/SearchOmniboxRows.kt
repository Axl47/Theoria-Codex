package com.theoriacodex.app.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.codex.CodexCoverCandidate
import com.theoriacodex.app.codex.CodexCoverImage
import com.theoriacodex.app.search.omnibox.OmniboxEntry
import com.theoriacodex.app.search.omnibox.OmniboxKind

/**
 * The composer's non-tag matches, after its leading tag suggestions: picking an [OmniboxEntry.Action] closes the
 * composer ([onLeave]) and runs it; a [OmniboxEntry.Toggle] switches in place.
 */
internal fun LazyListScope.omniboxRows(entries: List<OmniboxEntry>, afterTags: Boolean, onLeave: () -> Unit) {
    if (entries.isEmpty()) return
    if (afterTags) item { HorizontalDivider() }
    items(entries) { entry ->
        when (entry) {
            is OmniboxEntry.Action -> OmniboxRow(
                entry,
                Modifier.clickable(onClickLabel = "Open") {
                    onLeave()
                    entry.run()
                },
            ) { OmniboxText(entry.title, entry.detail) }
            is OmniboxEntry.Toggle -> OmniboxRow(
                entry,
                Modifier.toggleable(entry.checked, role = Role.Switch, onValueChange = entry.set),
            ) {
                OmniboxText(entry.title, detail = null)
                Switch(checked = entry.checked, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun OmniboxRow(entry: OmniboxEntry, action: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("Search omnibox row")
            .then(action)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val (icon, label) = omniboxKindIcon(entry.kind)
        val kindIcon = @Composable { Icon(icon, label, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant) }
        val cover = (entry as? OmniboxEntry.Action)?.cover
        // Icons sit in a cover-wide slot so every row's text starts at the same edge.
        if (cover == null) Box(Modifier.width(COVER_SIZE), contentAlignment = Alignment.Center) { kindIcon() }
        else OmniboxCover(cover, label, kindIcon)
        content()
    }
}

private val COVER_SIZE = 40.dp

/** A thumbnail the size of two text lines, showing the kind's icon until (or unless) a cover loads. */
@Composable
private fun OmniboxCover(candidates: List<CodexCoverCandidate>, label: String, fallback: @Composable () -> Unit) {
    Box(
        Modifier
            .size(COVER_SIZE)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        CodexCoverImage(candidates, contentDescription = label, modifier = Modifier.fillMaxSize(), fallback = fallback)
    }
}

@Composable
private fun RowScope.OmniboxText(title: String, detail: String?) {
    Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Each kind's icon and what TalkBack calls it, so rows of different kinds read apart. */
private fun omniboxKindIcon(kind: OmniboxKind): Pair<ImageVector, String> = when (kind) {
    OmniboxKind.CODEX -> Icons.Default.CollectionsBookmark to "Codex"
    OmniboxKind.CREATOR -> Icons.Default.Person to "Creator"
    OmniboxKind.SAVED_SEARCH -> Icons.Default.Bookmark to "Saved search"
    OmniboxKind.RECENT_SEARCH -> Icons.AutoMirrored.Filled.ManageSearch to "Recent search"
    OmniboxKind.COMMAND -> Icons.Default.Settings to "Setting"
}
