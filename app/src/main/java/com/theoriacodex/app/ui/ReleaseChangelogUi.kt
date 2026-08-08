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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
            ReleaseChangelogEntryContent(release, installedVersionCode)
            if (index != releases.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun ReleaseChangelogEntryContent(release: ReleaseChangelogEntry, installedVersionCode: Int) {
    val titleBase = releaseDisplayTitle(release.releaseName, release.versionCode)
    Text(
        if (release.versionCode == installedVersionCode) "$titleBase (Current)" else titleBase,
        style = MaterialTheme.typography.titleMedium,
    )
    val sections = release.changelogSections.filter { it.bullets.isNotEmpty() }
    if (sections.isNotEmpty()) {
        sections.forEach { section ->
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
