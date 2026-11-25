package com.theoriacodex.app.series

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.seriesKind
import kotlinx.coroutines.flow.first

@Composable
internal fun SwitchingSource(from: Series, create: (SeriesId) -> SwitchSourceViewModel, onOpenSeries: (SeriesId) -> Unit, onDone: () -> Unit) {
    val switchSource = viewModel(key = "$SWITCHING_SOURCE_KEY${from.id}") { create(from.id) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(switchSource) {
        switchSource.state.first { it.others.isNotEmpty() }
        switchSource.open(from.title)
        switchSource.state.first { it.sheet == null }
        done()
    }
    SwitchSourceSheet(switchSource, onOpenSeries, onSwitched = {})
}

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
            OutlinedTextField(
                value = sheet.query,
                onValueChange = viewModel::editQuery,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                enabled = !sheet.switching,
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.searchAgain() }),
            )
            if (sheet.switching) LinearProgressIndicator(Modifier.fillMaxWidth())
            sheet.failure?.let { Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
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

private const val SWITCHING_SOURCE_KEY = "switching-source-"
