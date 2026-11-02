package com.theoriacodex.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.update.ReleaseChangelogEntry

@Composable
internal fun StartupUpdatePromptCard(
    releases: List<ReleaseChangelogEntry>,
    installedVersionCode: Int,
    actionEnabled: Boolean,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onRemindLater: () -> Unit,
) {
    val latestRelease = releases.firstOrNull()
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Update available", style = MaterialTheme.typography.titleLarge)
            if (latestRelease != null) {
                val subtitleParts = buildList {
                    add(releaseDisplayTitle(latestRelease.releaseName, latestRelease.versionCode))
                    add("vc${latestRelease.versionCode}")
                    add(latestRelease.commitShaShort)
                }
                Text(subtitleParts.joinToString(separator = " • "), style = MaterialTheme.typography.bodySmall)
            }
            ReleaseChangelogList(releases, installedVersionCode, 240.dp, 10.dp)
            StartupUpdatePromptActions(actionEnabled, onYes, onNo, onRemindLater)
        }
    }
}

@Composable
internal fun PostInstallChangelogDialog(
    releases: List<ReleaseChangelogEntry>,
    installedVersionCode: Int,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("What's new") },
        text = { ReleaseChangelogList(releases, installedVersionCode, 320.dp, 14.dp) },
    )
}

@Composable
internal fun ReleaseHistoryDialog(
    releases: List<ReleaseChangelogEntry>,
    installedVersionCode: Int,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Release changelog") },
        text = { ReleaseChangelogList(releases, installedVersionCode, 420.dp, 14.dp) },
    )
}

@Composable
private fun ReleaseChangelogList(
    releases: List<ReleaseChangelogEntry>,
    installedVersionCode: Int,
    maxHeight: Dp,
    itemSpacing: Dp,
) {
    if (releases.isEmpty()) {
        Text("No changelog details were published for this build.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
        releases.forEachIndexed { index, release ->
            ReleaseChangelogEntryContent(release, installedVersionCode, initiallyExpanded = index == 0)
            if (index != releases.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun ReleaseChangelogEntryContent(
    release: ReleaseChangelogEntry,
    installedVersionCode: Int,
    initiallyExpanded: Boolean,
) {
    var expanded by rememberSaveable(release.versionCode) { mutableStateOf(initiallyExpanded) }
    val titleBase = releaseDisplayTitle(release.releaseName, release.versionCode)
    Row(
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .clickable(role = Role.Button) { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (release.versionCode == installedVersionCode) "$titleBase (Current)" else titleBase,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(if (expanded) "−" else "+", color = MaterialTheme.colorScheme.primary)
    }
    if (!expanded) return
    val sections = release.changelogSections.filter { it.bullets.isNotEmpty() }
    val (highlights, details) = sections.partition { it.title.trim().equals("Highlights", ignoreCase = true) }
    var detailsExpanded by rememberSaveable(release.versionCode) { mutableStateOf(false) }
    if (highlights.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Highlights", style = MaterialTheme.typography.titleMedium)
                highlights.flatMap { it.bullets }.forEach { bullet ->
                    Text("• ${bullet.trim()}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (details.isNotEmpty()) {
            TextButton(
                onClick = { detailsExpanded = !detailsExpanded },
                modifier = Modifier.semantics {
                    stateDescription = if (detailsExpanded) "Expanded" else "Collapsed"
                },
            ) {
                Text(if (detailsExpanded) "Full details −" else "Full details +")
            }
        }
    }
    if (highlights.isNotEmpty() && !detailsExpanded) return
    if (sections.isNotEmpty()) {
        details.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(section.title, style = MaterialTheme.typography.titleSmall)
                section.bullets.forEach { ChangelogBulletText(it) }
            }
        }
    } else {
        Text(
            firstChangelogLine(release.changelogMarkdown)
                ?: "No changelog details were published for this build.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal fun releaseDisplayTitle(releaseName: String?, versionCode: Int): String {
    return releaseName?.trim()?.takeIf(String::isNotBlank) ?: "vc$versionCode"
}

internal fun firstChangelogLine(markdown: String): String? {
    return markdown.lineSequence()
        .map(String::trim)
        .firstOrNull { line -> line.isNotBlank() && !line.startsWith("#") }
}
