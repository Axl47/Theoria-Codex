package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
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
    val report by produceState<String?>(null, sections, generation) { value = entry.build(sections) }
    SettingsPageColumn {
        SettingsHeader("Diagnostics report", onBack, SETTINGS_PATH)
        StatsCaption(
            "Send this to help track down a problem. Web addresses, keys and account details are removed first, " +
                "and the report only leaves your phone if you share it.",
        )
        StatsCard {
            DiagnosticSection.entries.forEach { section ->
                ShareCheckRow(section.label, section.summary, section in sections, { included ->
                    sections = if (included) sections + section else sections - section
                })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = {
                    busy = true
                    scope.launch { try { entry.clear(); generation += 1 } finally { busy = false } }
                },
            ) { Text("Delete saved logs") }
            Button(
                enabled = !busy && !report.isNullOrBlank(),
                modifier = Modifier.weight(1f),
                onClick = {
                    val text = report.orEmpty()
                    busy = true
                    scope.launch { try { entry.share(text) } finally { busy = false } }
                },
            ) { Text("Share report") }
        }
        StatsSectionTitle("Preview")
        StatsCard {
            val text = report
            Text(
                when {
                    text == null -> "Preparing report…"
                    text.isBlank() -> "Nothing selected."
                    else -> text.take(PREVIEW_CHARS) + if (text.length > PREVIEW_CHARS) "\n…and ${text.length - PREVIEW_CHARS} more characters" else ""
                },
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
