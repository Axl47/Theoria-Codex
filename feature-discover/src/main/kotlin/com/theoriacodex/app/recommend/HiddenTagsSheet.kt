package com.theoriacodex.app.recommend

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.components.ActionMenuSheet
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.app.ui.undo.UndoRow
import com.theoriacodex.data.repository.ForYouBlacklistEntry

val HiddenTagsUndoAnchor = UndoAnchor("hidden-tags")

@Composable
fun HiddenTagsSheet(
    entries: List<ForYouBlacklistEntry>,
    onUnhide: (ForYouBlacklistEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    ActionMenuSheet(onDismiss) {
        SheetHeader("Hidden tags", "Close hidden tags", onDismiss)
        if (entries.isEmpty()) EmptyState("No hidden tags")
        entries.forEach { entry ->
            val tags = entry.tags.joinToString(" + ")
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tags, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        entry.source.displayName(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton({ onUnhide(entry) }, Modifier.semantics { contentDescription = "Unhide $tags" }) { Text("Unhide") }
            }
        }
        UndoRow(HiddenTagsUndoAnchor, Modifier.padding(top = 8.dp))
    }
}
