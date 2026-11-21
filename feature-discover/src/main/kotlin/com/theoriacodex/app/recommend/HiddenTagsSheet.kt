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
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.components.ActionMenuSheet
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.app.ui.undo.UndoRow
import com.theoriacodex.data.repository.ForYouBlacklistEntry

/** Where the hidden tags sheet draws Undo after an entry is removed. */
val HiddenTagsUndoAnchor = UndoAnchor("hidden-tags")

/**
 * The tags For You hides for the active profile, each with Remove; the host removes at once and offers Undo
 * at [HiddenTagsUndoAnchor]. The shell hosts it once and opens it through
 * [com.theoriacodex.app.ui.navigation.LocalHiddenTagsOpener].
 */
@Composable
fun HiddenTagsSheet(
    entries: List<ForYouBlacklistEntry>,
    onRemove: (ForYouBlacklistEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    ActionMenuSheet(onDismiss) {
        SheetHeader("Hidden tags", "Close hidden tags", onDismiss)
        if (entries.isEmpty()) EmptyState("No hidden tags")
        entries.forEach { entry ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.tags.joinToString(" + "), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        entry.source.displayName(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onRemove(entry) }) { Text("Remove") }
            }
        }
        UndoRow(HiddenTagsUndoAnchor, Modifier.padding(top = 8.dp))
    }
}
