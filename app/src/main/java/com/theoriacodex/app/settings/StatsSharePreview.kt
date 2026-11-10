package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.statistics.CodexStatisticsSummary
import com.theoriacodex.app.statistics.StatsShareCodex
import com.theoriacodex.app.statistics.StatsShareFile
import com.theoriacodex.app.statistics.StatsShareSelection
import com.theoriacodex.app.statistics.buildStatsShare
import com.theoriacodex.app.statistics.compareStats
import com.theoriacodex.app.statistics.hasShareableActivity
import com.theoriacodex.app.statistics.shareCodex
import kotlinx.coroutines.launch

private val StringSetSaver = listSaver<Set<String>, String>({ it.toList() }, { it.toSet() })

/** Shows exactly what the file will hold; every box left unchecked is recalculated out of the totals. */
@Composable
internal fun StatsSharePreview(
    codices: List<CodexStatisticsSummary>,
    onShare: suspend (StatsShareFile) -> Unit,
    onBack: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var excludedCodices by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(emptySet<String>()) }
    var excludedPosts by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(emptySet<String>()) }
    var sharing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shareable = remember(codices) { codices.filter { it.hasShareableActivity } }
    val file = remember(codices, excludedCodices, excludedPosts, name) {
        buildStatsShare(codices, StatsShareSelection(excludedCodices, excludedPosts), name)
    }
    val totals = remember(file) { compareStats(file, StatsShareFile()).yours }
    SettingsPageColumn {
        SettingsHeader("Share my stats", onBack, SETTINGS_PATH + "Stats")
        StatsCaption("The file holds Codex names, time, open counts and your top posts. Uncheck anything you'd rather keep private.")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            label = { Text("Name they'll see (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        StatsSectionTitle("They'll see")
        StatsCard {
            ShareTotal("Time watching", formatStatsDuration(totals.watchingMs))
            ShareTotal("Time browsing", formatStatsDuration(totals.browsingMs))
            ShareTotal("Codex opens", totals.entries.toString())
            ShareTotal("Post opens", totals.postOpens.toString())
        }
        StatsSectionTitle("Codices")
        if (shareable.isEmpty()) StatsCaption("No Codex activity to share yet.")
        shareable.forEach { codex ->
            ShareCodexRow(
                codex = codex,
                included = codex.codexId !in excludedCodices,
                excludedPosts = excludedPosts,
                onIncludedChange = { excludedCodices = if (it) excludedCodices - codex.codexId else excludedCodices + codex.codexId },
                onPostIncludedChange = { key, included -> excludedPosts = if (included) excludedPosts - key else excludedPosts + key },
            )
        }
        Button(
            enabled = !sharing && file.codices.orEmpty().isNotEmpty(),
            onClick = {
                sharing = true
                scope.launch { try { onShare(file) } finally { sharing = false } }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Share file") }
    }
}

@Composable
private fun ShareTotal(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ShareCodexRow(
    codex: CodexStatisticsSummary,
    included: Boolean,
    excludedPosts: Set<String>,
    onIncludedChange: (Boolean) -> Unit,
    onPostIncludedChange: (String, Boolean) -> Unit,
) {
    var expanded by rememberSaveable(codex.codexId) { mutableStateOf(false) }
    val shared = remember(codex, excludedPosts) { shareCodex(codex, excludedPosts) }
    StatsCard {
        ShareCodexHeader(codex.name, shared, included, expanded, onIncludedChange) { expanded = !expanded }
        if (included && expanded) ShareCodexPosts(codex, shared, excludedPosts, onPostIncludedChange)
    }
}

@Composable
private fun ShareCodexHeader(
    name: String,
    shared: StatsShareCodex,
    included: Boolean,
    expanded: Boolean,
    onIncludedChange: (Boolean) -> Unit,
    onToggleExpanded: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShareCheckRow(
            title = name,
            summary = "${formatStatsDuration(shared.watchingMs)} watched · ${shared.postOpens} post opens",
            checked = included,
            onCheckedChange = onIncludedChange,
            modifier = Modifier.weight(1f),
        )
        if (included) IconButton(onClick = onToggleExpanded) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                if (expanded) "Hide posts in $name" else "Choose posts in $name",
            )
        }
    }
}

/** Unchecked posts stay listed so they can be put back; the next-ranked post has already taken their place. */
@Composable
private fun ShareCodexPosts(
    codex: CodexStatisticsSummary,
    shared: StatsShareCodex,
    excludedPosts: Set<String>,
    onPostIncludedChange: (String, Boolean) -> Unit,
) {
    val sharedPosts = shared.posts.orEmpty().filterNotNull().map { it.key.orEmpty() to it.label.orEmpty() }
    val removed = codex.posts.filterKeys(excludedPosts::contains).map { (key, post) -> key to post.label.ifBlank { key } }
    if (sharedPosts.isEmpty() && removed.isEmpty()) StatsCaption("No posts recorded here.")
    (sharedPosts + removed).forEach { (key, label) ->
        ShareCheckRow(label, null, key !in excludedPosts, { onPostIncludedChange(key, it) }, Modifier.padding(start = 16.dp))
    }
}

@Composable
internal fun ShareCheckRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            summary?.let { StatsCaption(it) }
        }
    }
}
