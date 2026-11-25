package com.theoriacodex.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.SortChoice
import com.theoriacodex.domain.model.SortDirection

data class SortOption<K>(val key: K, val label: String)

val SortDirection.icon: ImageVector
    get() = if (this == SortDirection.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward

val SortDirection.label: String
    get() = if (this == SortDirection.ASCENDING) "Ascending" else "Descending"

fun <K> List<SortOption<K>>.labelOf(key: K): String = first { it.key == key }.label

@Composable
fun SortDirectionMark(direction: SortDirection?, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Icon(direction?.icon ?: Icons.Default.Check, contentDescription = null, tint = tint, modifier = modifier.size(18.dp))
}

@Composable
fun SortLabel(label: String, direction: SortDirection?, onClick: () -> Unit, modifier: Modifier = Modifier, actionLabel: String = REVERSE_ORDER) {
    TextButton(
        onClick = onClick,
        modifier = modifier.semantics {
            direction?.let { stateDescription = it.label }
            onClick(label = actionLabel) { onClick(); true }
        },
    ) {
        Text(label, maxLines = 1)
        direction?.let { SortDirectionMark(it, Modifier.padding(start = 4.dp), tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
fun SortOptionRow(label: String, active: Boolean, direction: SortDirection?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                selected = active
                if (active) direction?.let { stateDescription = it.label }
            }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (active) SortDirectionMark(direction, tint = color)
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
fun <K> SortMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<SortOption<K>>,
    choice: SortChoice<K>,
    onPick: (K) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        options.forEach { option ->
            val active = option.key == choice.key
            DropdownMenuItem(
                text = {
                    Text(
                        option.label,
                        color = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        fontWeight = if (active) FontWeight.Medium else null,
                    )
                },
                leadingIcon = { Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { if (active) SortDirectionMark(choice.direction) } },
                modifier = Modifier.semantics {
                    selected = active
                    if (active) choice.direction?.let { stateDescription = it.label }
                },
                onClick = {
                    onDismiss()
                    onPick(option.key)
                },
            )
        }
    }
}

const val REVERSE_ORDER = "Reverse order"
