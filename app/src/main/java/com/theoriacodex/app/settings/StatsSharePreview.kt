package com.theoriacodex.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.statistics.CodexStatisticsSummary
import com.theoriacodex.app.statistics.ProfileStatistics
import com.theoriacodex.app.statistics.StatsShareFile
import com.theoriacodex.app.statistics.StatsShareSelection
import com.theoriacodex.app.statistics.StatsShareTotals
import com.theoriacodex.app.statistics.allProfileCodices
import com.theoriacodex.app.statistics.buildStatsShare
import com.theoriacodex.app.statistics.codexIdsOutsideProfiles
import com.theoriacodex.app.statistics.compareStats
import com.theoriacodex.app.statistics.hasShareableActivity
import com.theoriacodex.app.statistics.shareCodex
import com.theoriacodex.app.viewer.decodeViewerPostId
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import java.io.File
import kotlinx.coroutines.launch

private val StringSetSaver = listSaver<Set<String>, String>({ it.toList() }, { it.toSet() })

/** This set with [key] present when [present] is true, and absent otherwise. */
private fun Set<String>.withMember(key: String, present: Boolean): Set<String> = if (present) this + key else this - key

/** Shows exactly what the file will hold; everything left unchecked is recalculated out of the totals. */
@Composable
internal fun StatsSharePreview(
    profiles: List<ProfileStatistics>,
    activeProfileId: String,
    actions: StatsActions,
    onOpenPost: (PostId) -> Unit,
    onBack: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    // Starts with the profile in use: another profile may be private, so including it takes a deliberate tap.
    var includedProfiles by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(setOf(activeProfileId)) }
    var excludedCodices by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(emptySet<String>()) }
    var excludedPosts by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(emptySet<String>()) }
    var openCodexId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedProfileId by rememberSaveable { mutableStateOf(activeProfileId) }
    val codices = remember(profiles) { allProfileCodices(profiles) }
    val openCodex = codices.firstOrNull { it.codexId == openCodexId }
    if (openCodex != null) {
        BackHandler { openCodexId = null }
        ShareCodexPosts(
            openCodex, excludedPosts, actions.storedPost, onOpenPost,
            onIncludedChange = { key, included -> excludedPosts = excludedPosts.withMember(key, !included) },
            onBack = { openCodexId = null },
        )
        return
    }
    val outsideProfiles = remember(profiles, includedProfiles) { codexIdsOutsideProfiles(profiles, includedProfiles) }
    val file = remember(codices, outsideProfiles, excludedCodices, excludedPosts, name) {
        buildStatsShare(codices, StatsShareSelection(excludedCodices + outsideProfiles, excludedPosts), name)
    }
    val totals = remember(file) { compareStats(file, StatsShareFile()).yours }
    val selected = profiles.firstOrNull { it.profileId == selectedProfileId } ?: profiles.firstOrNull()
    SettingsPageColumn {
        SettingsHeader("Share my stats", onBack, SETTINGS_PATH)
        StatsCaption("The file holds Codex names, time, open counts and your top posts. Uncheck anything you'd rather keep private.")
        if (profiles.size > 1 && selected != null) {
            ShareProfileTabs(profiles, selected.profileId, includedProfiles) { selectedProfileId = it }
            ShareProfileToggle(selected, selected.profileId in includedProfiles) { included ->
                includedProfiles = includedProfiles.withMember(selected.profileId, included)
            }
        }
        ShareNameCard(name) { name = it.take(60) }
        ShareTotalsCard(totals, codexCount = file.codices.orEmpty().size, profileCount = includedProfiles.size)
        StatTiles("Codex opens" to totals.entries, "Post opens" to totals.postOpens)
        ShareCodices(
            selected?.codices.orEmpty(), excludedCodices, outsideProfiles, excludedPosts,
            onIncludedChange = { id, included -> excludedCodices = excludedCodices.withMember(id, !included) },
            onOpen = { openCodexId = it },
        )
        ShareButtons(file, actions)
    }
}

/** Each chip switches the list below; its check shows whether that profile is part of the file. */
@Composable
private fun ShareProfileTabs(
    profiles: List<ProfileStatistics>,
    selectedProfileId: String,
    includedProfiles: Set<String>,
    onSelect: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        profiles.forEach { profile ->
            FilterChip(
                selected = profile.profileId == selectedProfileId,
                onClick = { onSelect(profile.profileId) },
                label = { Text(profile.name) },
                leadingIcon = if (profile.profileId in includedProfiles) {
                    { Icon(Icons.Default.Check, "Included", Modifier.size(FilterChipDefaults.IconSize)) }
                } else null,
            )
        }
    }
}

@Composable
private fun ShareProfileToggle(profile: ProfileStatistics, included: Boolean, onIncludedChange: (Boolean) -> Unit) {
    val count = profile.codices.count { it.hasShareableActivity }
    SettingsGroup(null) {
        SettingsRow(
            "Include ${profile.name}",
            if (count == 1) "1 Codex with activity" else "$count Codices with activity",
            onClick = { onIncludedChange(!included) },
            trailing = {
                Checkbox(
                    checked = included,
                    onCheckedChange = onIncludedChange,
                    modifier = Modifier.semantics { contentDescription = "Include profile ${profile.name}" },
                )
            },
        )
    }
}

@Composable
private fun ShareButtons(file: StatsShareFile, actions: StatsActions) {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val enabled = !busy && file.codices.orEmpty().isNotEmpty()
    val run = { action: suspend (StatsShareFile) -> Unit ->
        busy = true
        scope.launch { try { action(file) } finally { busy = false } }
        Unit
    }
    Button(enabled = enabled, onClick = { run(actions.share) }, modifier = Modifier.fillMaxWidth()) { Text("Share file") }
    OutlinedButton(enabled = enabled, onClick = { run(actions.download) }, modifier = Modifier.fillMaxWidth()) { Text("Download") }
}

@Composable
private fun ShareNameCard(name: String, onNameChange: (String) -> Unit) {
    val textStyle = MaterialTheme.typography.titleLarge
    StatsCard {
        StatsCaption("Name they'll see")
        BasicTextField(
            value = name,
            onValueChange = onNameChange,
            singleLine = true,
            textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Name they'll see" },
            decorationBox = { field ->
                if (name.isEmpty()) Text("Optional", style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                field()
            },
        )
    }
}

@Composable
private fun ShareTotalsCard(totals: StatsShareTotals, codexCount: Int, profileCount: Int) {
    val codexText = if (codexCount == 1) "1 Codex" else "$codexCount Codices"
    StatsCard {
        StatsCaption("They'll see")
        StatsHeadline(formatStatsDuration(totals.browsingMs + totals.watchingMs))
        StatsCaption(if (profileCount > 1) "across $codexText from $profileCount profiles" else "across $codexText")
        TimeSplit(
            listOf(
                TimeSegment("Browsing", totals.browsingMs, BrowsingColor),
                TimeSegment("Watching", totals.watchingMs, WatchingColor),
            ),
        )
    }
}

/** The same list as Stats → Codices, with a checkbox for leaving a Codex out. */
@Composable
private fun ShareCodices(
    codices: List<CodexStatisticsSummary>,
    excludedCodices: Set<String>,
    outsideProfiles: Set<String>,
    excludedPosts: Set<String>,
    onIncludedChange: (String, Boolean) -> Unit,
    onOpen: (String) -> Unit,
) {
    val shareable = remember(codices) { codices.filter { it.hasShareableActivity } }
    StatsSectionTitle("Codices")
    if (shareable.isEmpty()) {
        StatsCaption("No Codex activity to share yet.")
        return
    }
    SettingsGroup(null) {
        shareable.forEachIndexed { index, codex ->
            if (index > 0) SettingsRowDivider()
            val available = codex.codexId !in outsideProfiles
            val included = available && codex.codexId !in excludedCodices
            val shared = remember(codex, excludedPosts) { shareCodex(codex, excludedPosts) }
            SettingsRow(
                codex.name,
                if (included) "${shared.entries} opens · ${formatStatsDuration(shared.watchingMs)} watched" else "Left out",
                enabled = included,
                onClick = { onOpen(codex.codexId) },
                trailing = {
                    Checkbox(
                        checked = included,
                        enabled = available,
                        onCheckedChange = { onIncludedChange(codex.codexId, it) },
                        modifier = Modifier.semantics { contentDescription = "Include ${codex.name}" },
                    )
                },
            )
        }
    }
}

/** Rows open the post so it can be inspected; only the checkbox changes what is shared. */
@Composable
private fun ShareCodexPosts(
    codex: CodexStatisticsSummary,
    excludedPosts: Set<String>,
    storedPost: suspend (PostId) -> Post?,
    onOpenPost: (PostId) -> Unit,
    onIncludedChange: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val shared = remember(codex, excludedPosts) { shareCodex(codex, excludedPosts) }
    val rows = shared.posts.orEmpty().filterNotNull().mapNotNull { it.key } +
        // Unchecked posts stay listed so they can be put back; the next-ranked post has already taken their place.
        codex.posts.keys.filter(excludedPosts::contains)
    val totalOpens = codex.posts.values.sumOf { it.opens }.coerceAtLeast(1L)
    SettingsPageColumn {
        SettingsHeader(codex.name, onBack, SETTINGS_PATH + "Share my stats")
        StatsCaption("Tap a post to look at it. Uncheck it to leave it out.")
        StatsCard {
            if (rows.isEmpty()) StatsCaption("No posts recorded here.")
            rows.forEach { key ->
                val post = codex.posts.getValue(key)
                val label = post.label.ifBlank { key }
                val percentage = (post.opens * 100 / totalOpens).toInt()
                StatsPostRow(
                    postKey = key,
                    label = label,
                    value = if (post.opens > 0L) "$percentage% · ${post.opens}" else formatStatsDuration(post.videoMs + post.imageMs),
                    percentage = percentage,
                    thumbnail = rememberShareThumbnail(key, codex.thumbnails[key], storedPost),
                    reserveThumbnail = true,
                    onOpen = onOpenPost,
                ) {
                    Checkbox(
                        checked = key !in excludedPosts,
                        onCheckedChange = { onIncludedChange(key, it) },
                        modifier = Modifier.padding(start = 2.dp).semantics { contentDescription = "Include $label" },
                    )
                }
            }
        }
    }
}

/** Only ranked posts keep a Stats thumbnail, so a post that just moved up falls back to its saved preview. */
@Composable
private fun rememberShareThumbnail(key: String, retainedPath: String?, storedPost: suspend (PostId) -> Post?): Any? {
    val context = LocalContext.current
    val saved by produceState<Post?>(null, key, retainedPath) {
        if (retainedPath == null) value = decodeViewerPostId(key)?.let { storedPost(it) }
    }
    return remember(retainedPath, saved, context) {
        retainedPath?.let(::File) ?: saved?.let { post ->
            post.preview.localPath?.let(::File) ?: post.preview.url?.let {
                MediaRequestFactory.imageRequest(context, it, post.id.source, crossfade = false, staticAnimatedWebPFrame = true)
            }
        }
    }
}
