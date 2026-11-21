package com.theoriacodex.app.tags

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A tag chip where a tap forks a trail from the tag ([onFork]) and a long-press opens [moves]: the Search draft edits and
 * saving that a tap and hold do on other screens. TalkBack reaches each move directly as a custom action.
 */
@Composable
internal fun TagForkChip(
    name: String,
    label: String,
    count: String?,
    compact: Boolean,
    state: TagChipState,
    leadingIcon: ImageVector?,
    stateSuffix: String?,
    moves: List<TagChipAction>,
    onFork: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        TagPickerChip(
            label = label,
            count = count,
            compact = compact,
            state = state,
            tapLabel = "Start a trail from $name",
            onTap = onFork,
            alternateActions = moves,
            onLongPress = { menuOpen = true }.takeIf { moves.isNotEmpty() },
            leadingIcon = leadingIcon,
            stateSuffix = stateSuffix,
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            moves.forEach { move ->
                DropdownMenuItem(
                    text = { Text(move.label) },
                    onClick = {
                        menuOpen = false
                        move.run()
                    },
                )
            }
        }
    }
}
