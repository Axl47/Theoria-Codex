package com.theoriacodex.app.series

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import com.theoriacodex.app.ui.components.FoldHeader
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.seriesKind

@Composable
internal fun SwitchSourceSheet(
    viewModel: SwitchSourceViewModel,
    onOpenSeries: (SeriesId) -> Unit,
    onSwitched: (SeriesId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheet = state.sheet ?: return
    ExpandedBottomSheet(onDismiss = viewModel::close, dismissible = !sheet.switching) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            SheetHeader(SWITCH_SOURCE, closeDescription = "Close", onDismiss = viewModel::close)
            SeriesSearchBar(
                query = sheet.query,
                placeholder = "Search",
                searching = false,
                focusRequest = 0,
                onQueryChange = viewModel::editQuery,
                onSearch = { viewModel.searchAgain() },
                onClear = { viewModel.editQuery("") },
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            SourceChecklist(state.others, sheet.skipped, enabled = !sheet.switching, onToggle = viewModel::toggle)
            HorizontalDivider(Modifier.padding(bottom = 4.dp))
            if (sheet.switching) LinearProgressIndicator(Modifier.fillMaxWidth())
            sheet.failure?.let { Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
            if (sheet.results.isEmpty() && state.others.isNotEmpty()) SourceLine("No sources", Modifier.padding(vertical = 12.dp))
            sheet.results.forEach { result ->
                SourceResult(result, enabled = !sheet.switching, onRetry = { viewModel.retry(result.key) }, onPick = { viewModel.confirm(it) })
            }
        }
    }
    sheet.confirming?.let { match ->
        AlertDialog(
            onDismissRequest = { viewModel.confirm(null) },
            title = { Text("Switch to ${sheet.sourceName(match.series.id.source)}?") },
            text = { Text(switchConsequence(match.series.id.source.seriesKind)) },
            confirmButton = { TextButton(onClick = { viewModel.switchTo(match, onSwitched) }) { Text("Switch") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.confirm(null) }) { Text("Cancel") }
                    TextButton(onClick = { viewModel.openMatch(match, onOpenSeries) }) { Text("Open") }
                }
            },
        )
    }
}

@Composable
private fun SourceChecklist(others: List<SeriesSource>, skipped: Set<SourceKey>, enabled: Boolean, onToggle: (List<SourceKey>) -> Unit) {
    if (others.isEmpty()) return
    var collapsed by rememberSaveable { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val groups = remember(others) { sourceGroups(others) }
    FoldHeader(
        title = "Sources",
        what = "sources",
        count = "${others.count { it.key !in skipped }} of ${others.size}",
        collapsed = collapsed,
        onToggle = { collapsed = !collapsed },
    )
    if (collapsed) return
    groups.forEach { group ->
        val open = group.name in expanded
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceCheckRow(group.name, group.state(skipped), enabled, Modifier.weight(1f)) { onToggle(group.keys) }
            if (group.choices.size > 1) {
                IconButton(onClick = { expanded = if (open) expanded - group.name else expanded + group.name }) {
                    Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (open) "Hide languages" else "Show languages")
                }
            }
        }
        if (open && group.choices.size > 1) {
            group.choices.forEach { choice ->
                val state = if (choice.key in skipped) ToggleableState.Off else ToggleableState.On
                SourceCheckRow(choice.language, state, enabled, Modifier.padding(start = 32.dp)) { onToggle(listOf(choice.key)) }
            }
        }
    }
}

@Composable
private fun SourceCheckRow(label: String, state: ToggleableState, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OptionRow(label, modifier.triStateToggleable(state, enabled = enabled, role = Role.Checkbox, onClick = onClick)) {
        TriStateCheckbox(state, onClick = null, enabled = enabled)
    }
}

@Composable
private fun SourceResult(result: SwitchSourceResult, enabled: Boolean, onRetry: () -> Unit, onPick: (SwitchMatch) -> Unit) {
    when (val listing = result.listing) {
        SwitchListing.Loading -> Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceLine(result.name)
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        is SwitchListing.Failed -> FailureRow(onRetry, message = "${result.name} · ${listing.message}", style = MaterialTheme.typography.bodyMedium)
        is SwitchListing.Found ->
            if (listing.matches.isEmpty()) SourceLine("${result.name} · no match", Modifier.padding(vertical = 12.dp))
            else listing.matches.forEach { match -> MatchRow(match, result.name, enabled) { onPick(match) } }
    }
}

@Composable
private fun MatchRow(match: SwitchMatch, sourceName: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeriesCover(match.series, Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(match.series.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            SourceLine(match.entries?.let { "$sourceName · ${match.series.id.source.seriesKind.words.count(it)}" } ?: sourceName)
        }
    }
}

@Composable
private fun SourceLine(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun switchConsequence(kind: SeriesKind): String = when (kind) {
    SeriesKind.MANGA -> "The current series leaves the shelf and its downloads are deleted."
    SeriesKind.ANIME -> "The current series leaves the shelf."
}

internal const val SWITCH_SOURCE = "Switch source"
