package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.diagnostics.DiagnosticSection
import kotlinx.coroutines.launch

private const val PREVIEW_CHARS = 8_000

private val SectionSetSaver = listSaver<Set<DiagnosticSection>, String>(
    { sections -> sections.map { it.name } },
    { names -> DiagnosticSection.entries.filterTo(linkedSetOf()) { it.name in names } },
)

/** Lets someone read a redacted report and choose its parts before anything leaves the device. */
@Composable
internal fun DiagnosticsPage(entry: SettingsDiagnosticsEntry, onBack: () -> Unit) {
    var sections by rememberSaveable(stateSaver = SectionSetSaver) { mutableStateOf(DiagnosticSection.entries.toSet()) }
    var generation by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val run = { action: suspend () -> Unit ->
        busy = true
        scope.launch { try { action() } finally { busy = false } }
        Unit
    }
    val built by produceState<BuiltReport?>(null, sections, generation) {
        value = BuiltReport(sections, generation, entry.build(sections))
    }
    // A rebuild keeps showing its previous value, so only text built for the current choices may be used.
    val report = built?.takeIf { it.sections == sections && it.generation == generation }?.text
    SettingsPageColumn {
        SettingsHeader("Diagnostics report", onBack, SETTINGS_PATH)
        StatsCaption(
            "Send this to help track down a problem. Web addresses, keys and account details are removed first, " +
                "and the report only leaves your phone if you share it.",
        )
        StatsCard {
            DiagnosticSection.entries.forEach { section ->
                DiagnosticSectionRow(section, section in sections) { included ->
                    sections = if (included) sections + section else sections - section
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy && !report.isNullOrBlank(),
                modifier = Modifier.weight(1f),
                onClick = { run { entry.download(report.orEmpty()) } },
            ) { Text("Download") }
            Button(
                enabled = !busy && !report.isNullOrBlank(),
                modifier = Modifier.weight(1f),
                onClick = { run { entry.share(report.orEmpty()) } },
            ) { Text("Share report") }
        }
        StatsSectionTitle("Preview")
        DiagnosticPreview(report)
        OutlinedButton(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            onClick = { run { entry.clear(); generation += 1 } },
        ) { Text("Delete saved logs") }
    }
}

@Composable
private fun DiagnosticPreview(report: String?) {
    StatsCard {
        Text(
            when {
                report == null -> "Preparing report…"
                report.isBlank() -> "Nothing selected."
                else -> report.take(PREVIEW_CHARS) + if (report.length > PREVIEW_CHARS) "\n…and ${report.length - PREVIEW_CHARS} more characters" else ""
            },
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private data class BuiltReport(val sections: Set<DiagnosticSection>, val generation: Int, val text: String)

@Composable
private fun DiagnosticSectionRow(section: DiagnosticSection, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f)) {
            Text(section.label, style = MaterialTheme.typography.bodyMedium)
            StatsCaption(section.summary)
        }
    }
}
