package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.theoriacodex.app.statistics.CodexStatisticsSummary
import com.theoriacodex.app.statistics.CodexPostStatistic
import com.theoriacodex.app.statistics.formatStatisticsDuration

@Composable
internal fun IndividualCodexStatistics(codices: List<CodexStatisticsSummary>) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected = codices.firstOrNull { it.codexId == selectedId } ?: codices.firstOrNull()
    if (selected == null) {
        Text("No Codices yet.")
        return
    }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("${selected.name} ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            codices.forEach { codex ->
                DropdownMenuItem(text = { Text(codex.name) }, onClick = {
                    selectedId = codex.codexId
                    expanded = false
                })
            }
        }
    }
    CodexStatRow("Time Inside Codex", formatStatisticsDuration(selected.browsingMs + selected.watchingMs))
    CodexStatRow("Browsing", formatStatisticsDuration(selected.browsingMs))
    CodexStatRow("Watching", formatStatisticsDuration(selected.watchingMs))
    CodexStatRow("Times Opened Codex", selected.entries.toString())
    CodexStatRow("Posts Seen from Codex", selected.postsSeen.toString())
    CodexPostBreakdown("Top 5 Posts Opened", selected.topOpened, duration = false)
    CodexPostBreakdown("Top 5 Videos Watched (time)", selected.topVideos, duration = true)
    CodexPostBreakdown("Top 5 Images Watched (time)", selected.topImages, duration = true)
}

@Composable
private fun CodexStatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CodexPostBreakdown(title: String, rows: List<CodexPostStatistic>, duration: Boolean) {
    Text(title, style = MaterialTheme.typography.bodyMedium)
    if (rows.isEmpty()) Text("No activity yet.", style = MaterialTheme.typography.bodySmall)
    rows.forEachIndexed { index, row ->
        val amount = if (duration) formatStatisticsDuration(row.value) else "${row.value} opens"
        CodexStatRow("${index + 1}. ${row.label}", "$amount · ${row.percentage}%")
    }
}
