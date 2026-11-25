package com.theoriacodex.app.series

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.app.ui.components.SortOption
import com.theoriacodex.app.ui.components.SortOptionRow
import com.theoriacodex.domain.model.SortChoice

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <F, S> SortFilterSheet(
    filters: List<SortOption<F>>,
    filter: F,
    onFilter: (F) -> Unit,
    sorts: List<SortOption<S>>,
    sort: SortChoice<S>,
    onSort: (S) -> Unit,
    onReset: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ExpandedBottomSheet(onDismiss = onDismiss) {
        SheetHeader("Sort & filter", closeDescription = "Close", onDismiss = onDismiss) {
            if (onReset != null) TextButton(onClick = onReset) { Text("Reset") }
        }
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Filter")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    filters.forEach { choice ->
                        FilterChip(selected = filter == choice.key, onClick = { onFilter(choice.key) }, label = { Text(choice.label) })
                    }
                }
            }
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Sort")
                Spacer(Modifier.height(2.dp))
                sorts.forEach { choice ->
                    SortOptionRow(choice.label, active = sort.key == choice.key, sort.direction, onClick = { onSort(choice.key) })
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}
