package com.theoriacodex.app.series

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import com.theoriacodex.app.ui.components.SettingsGroup
import com.theoriacodex.app.ui.components.SettingsRowDivider
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.data.repository.FollowedCheck
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId

internal enum class CheckProblem(val label: String) {
    UNREACHABLE("Couldn't reach site"),
    TIMED_OUT("Timed out"),
    BLOCKED("Blocked"),
    RATE_LIMITED("Rate limited"),
    SOURCE_ERROR("Source error"),
}

internal val SourceFailureReason.problem: CheckProblem
    get() = when (this) {
        SourceFailureReason.NETWORK -> CheckProblem.UNREACHABLE
        SourceFailureReason.TIMED_OUT -> CheckProblem.TIMED_OUT
        SourceFailureReason.BLOCKED -> CheckProblem.BLOCKED
        SourceFailureReason.RATE_LIMITED -> CheckProblem.RATE_LIMITED
        SourceFailureReason.PARSE,
        SourceFailureReason.UNKNOWN,
        SourceFailureReason.AUTH_REQUIRED,
        SourceFailureReason.AUTH_EXPIRED,
        -> CheckProblem.SOURCE_ERROR
    }

internal data class ProblemGroup(val problem: CheckProblem, val series: List<Series>)

internal data class SourceFailures(val source: SourceKey, val name: String, val groups: List<ProblemGroup>)

internal fun FollowedCheck.stillFollowed(followed: Set<SeriesId>): FollowedCheck = copy(failures = failures.filter { it.series.id in followed })

internal fun FollowedCheck.bySource(name: (SourceKey) -> String?): List<SourceFailures> =
    failures.groupBy { it.series.id.source }.map { (source, failed) ->
        SourceFailures(
            source,
            name(source) ?: source.id,
            failed.groupBy { it.reason.problem }.entries.sortedBy { it.key }.map { (problem, group) -> ProblemGroup(problem, group.map { it.series }) },
        )
    }.sortedBy { it.name.lowercase() }

@Composable
internal fun CheckFailedCard(message: String, onOpen: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.AutoMirrored.Default.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("Retry") }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Dismiss") }
        }
    }
}

@Composable
internal fun CheckFailuresSheet(sources: List<SourceFailures>, titles: TitleActions, onRetry: (Set<SeriesId>) -> Unit, onDismiss: () -> Unit) {
    var toggled by rememberSaveable { mutableStateOf(emptySet<String>()) }
    ExpandedBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            SheetHeader(COULDNT_CHECK, closeDescription = "Close", onDismiss = onDismiss)
            sources.forEach { source ->
                SettingsGroup(source.name) {
                    source.groups.forEachIndexed { index, group ->
                        if (index > 0) SettingsRowDivider()
                        val key = "${source.source.id}/${group.problem.name}"
                        val open = (group.series.size <= COLLAPSE_OVER) != (key in toggled)
                        ProblemRow(group, open, onToggle = { toggled = if (key in toggled) toggled - key else toggled + key }) {
                            onRetry(group.series.mapTo(HashSet()) { it.id })
                        }
                        if (open) group.series.forEach { FollowedTitleRow(it, nested = true, titles) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProblemRow(group: ProblemGroup, open: Boolean, onToggle: () -> Unit, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(group.problem.label, style = MaterialTheme.typography.bodyLarge)
            Text("${group.series.size} series", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onRetry) { Text("Retry") }
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
    }
}

internal const val COULDNT_CHECK = "Couldn't check"
private const val COLLAPSE_OVER = 3
