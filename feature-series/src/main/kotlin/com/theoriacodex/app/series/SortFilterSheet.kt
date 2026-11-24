package com.theoriacodex.app.series

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import com.theoriacodex.app.ui.components.SheetHeader

/** One choice a [SortFilterSheet] offers: its [value] and what it is called. */
internal data class SheetChoice<T>(val value: T, val label: String)

/**
 * A "Sort & filter" sheet: one filter chosen among [filters] as chips, then one order among [sorts] as rows, the
 * chosen one marked by [sortMark] (with [sortMarkDescription] for accessibility). Reset shows when [onReset] is given.
 * The chapter list and the Following grid share it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <F, S> SortFilterSheet(
    filters: List<SheetChoice<F>>,
    filter: F,
    onFilter: (F) -> Unit,
    sorts: List<SheetChoice<S>>,
    sort: S,
    onSort: (S) -> Unit,
    sortMark: ImageVector,
    sortMarkDescription: String?,
    onReset: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ExpandedBottomSheet(onDismiss = onDismiss) {
        SheetHeader("Sort & filter", closeDescription = "Close", onDismiss = onDismiss) {
            if (onReset != null) TextButton(onClick = onReset) { Text("Reset") }
        }
        Column(
            // Scrolls when the chips wrap tall, as in landscape or with large text.
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Filter")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    filters.forEach { choice ->
                        FilterChip(selected = filter == choice.value, onClick = { onFilter(choice.value) }, label = { Text(choice.label) })
                    }
                }
            }
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Sort")
                Spacer(Modifier.height(2.dp))
                sorts.forEach { choice ->
                    SortRow(choice.label, active = sort == choice.value, sortMark, sortMarkDescription) { onSort(choice.value) }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SortRow(label: String, active: Boolean, mark: ImageVector, markDescription: String?, onClick: () -> Unit) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = active }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (active) Icon(mark, contentDescription = markDescription, tint = color, modifier = Modifier.size(18.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
    }
}
