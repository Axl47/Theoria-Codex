package com.theoriacodex.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import com.theoriacodex.app.statistics.StatsShareFile
import com.theoriacodex.app.statistics.StatsShareSelection
import com.theoriacodex.app.statistics.StatsShareTotals
import com.theoriacodex.app.statistics.buildStatsShare
import com.theoriacodex.app.statistics.compareStats
import com.theoriacodex.app.statistics.hasShareableActivity
import com.theoriacodex.app.statistics.shareCodex
import com.theoriacodex.app.viewer.decodeViewerPostId
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import java.io.File
import kotlinx.coroutines.launch

private val StringSetSaver = listSaver<Set<String>, String>({ it.toList() }, { it.toSet() })

/** Shows exactly what the file will hold; everything left unchecked is recalculated out of the totals. */
@Composable
internal fun StatsSharePreview(
    codices: List<CodexStatisticsSummary>,
    onShare: suspend (StatsShareFile) -> Unit,
    storedPost: suspend (PostId) -> Post?,
    onOpenPost: (PostId) -> Unit,
    onBack: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var excludedCodices by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(emptySet<String>()) }
    var excludedPosts by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(emptySet<String>()) }
    var openCodexId by rememberSaveable { mutableStateOf<String?>(null) }
    val openCodex = codices.firstOrNull { it.codexId == openCodexId }
    if (openCodex != null) {
        BackHandler { openCodexId = null }
        ShareCodexPosts(
            openCodex, excludedPosts, storedPost, onOpenPost,
            onIncludedChange = { key, included -> excludedPosts = if (included) excludedPosts - key else excludedPosts + key },
            onBack = { openCodexId = null },
        )
        return
    }
    var sharing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val file = remember(codices, excludedCodices, excludedPosts, name) {
        buildStatsShare(codices, StatsShareSelection(excludedCodices, excludedPosts), name)
    }
    val totals = remember(file) { compareStats(file, StatsShareFile()).yours }
    SettingsPageColumn {
        SettingsHeader("Share my stats", onBack, SETTINGS_PATH)
        StatsCaption("The file holds Codex names, time, open counts and your top posts. Uncheck anything you'd rather keep private.")
        ShareNameCard(name) { name = it.take(60) }
        ShareTotalsCard(totals, codexCount = file.codices.orEmpty().size)
        StatTiles("Codex opens" to totals.entries, "Post opens" to totals.postOpens)
        ShareCodices(
            codices, excludedCodices, excludedPosts,
            onIncludedChange = { id, included -> excludedCodices = if (included) excludedCodices - id else excludedCodices + id },
            onOpen = { openCodexId = it },
        )
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
private fun ShareTotalsCard(totals: StatsShareTotals, codexCount: Int) {
    StatsCard {
        StatsCaption("They'll see")
        StatsHeadline(formatStatsDuration(totals.browsingMs + totals.watchingMs))
        StatsCaption(if (codexCount == 1) "across 1 Codex" else "across $codexCount Codices")
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
            val included = codex.codexId !in excludedCodices
            val shared = remember(codex, excludedPosts) { shareCodex(codex, excludedPosts) }
            SettingsRow(
                codex.name,
                if (included) "${shared.entries} opens · ${formatStatsDuration(shared.watchingMs)} watched" else "Left out",
                enabled = included,
                onClick = { onOpen(codex.codexId) },
                trailing = {
                    Checkbox(
                        checked = included,
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
