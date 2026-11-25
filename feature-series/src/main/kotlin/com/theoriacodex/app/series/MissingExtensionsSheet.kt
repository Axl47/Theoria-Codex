package com.theoriacodex.app.series

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import com.theoriacodex.app.ui.components.LoadingState
import com.theoriacodex.app.ui.components.SettingsGroup
import com.theoriacodex.app.ui.components.SettingsRowDivider
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.seriesKind
import kotlinx.coroutines.flow.first

class MissingExtensionsActions(
    val extensions: () -> ExtensionsViewModel,
    val switchSource: (SeriesId) -> SwitchSourceViewModel,
    val onOpenRepos: () -> Unit,
)

@Composable
internal fun MissingExtensionsOverlay(
    content: SeriesTabContent,
    actions: MissingExtensionsActions,
    onUnfollow: (SeriesId) -> Unit,
    onOpenSeries: (SeriesId) -> Unit,
    onDismiss: () -> Unit,
) {
    val missing = content.missingExtensions
    val resolved = content.sources != null && content.following != null && missing.isEmpty()
    LaunchedEffect(resolved) { if (resolved) onDismiss() }
    val extensions = viewModel(key = MISSING_EXTENSIONS_KEY) { actions.extensions() }
    var resumed by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(extensions) {
        if (resumed || extensions.state.value.listing != null) extensions.reloadListing()
        resumed = true
        onPauseOrDispose {}
    }
    var switching by remember { mutableStateOf<Series?>(null) }
    val from = switching
    if (from == null) {
        val kinds = content.sources.orEmpty().mapTo(HashSet()) { it.key.seriesKind }
        MissingExtensionsSheet(
            missing = missing,
            extensions = extensions,
            canSwitch = { it.id.source.seriesKind in kinds },
            onSwitch = { switching = it },
            onUnfollow = onUnfollow,
            onOpenRepos = actions.onOpenRepos,
            onDismiss = onDismiss,
        )
    } else {
        val switchSource = viewModel(key = "$SWITCH_SOURCE_KEY${from.id}") { actions.switchSource(from.id) }
        LaunchedEffect(switchSource) {
            switchSource.state.first { it.others.isNotEmpty() }
            switchSource.open(from.title)
            switchSource.state.first { it.sheet == null }
            switching = null
        }
        SwitchSourceSheet(switchSource, onOpenSeries = onOpenSeries, onSwitched = {})
    }
}

@Composable
private fun MissingExtensionsSheet(
    missing: List<Series>,
    extensions: ExtensionsViewModel,
    canSwitch: (Series) -> Boolean,
    onSwitch: (Series) -> Unit,
    onUnfollow: (SeriesId) -> Unit,
    onOpenRepos: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by extensions.state.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val title: @Composable (Series, Boolean) -> Unit = { series, nested ->
        MissingTitleRow(series, nested, canSwitch(series), onSwitch = { onSwitch(series) }, onUnfollow = { onUnfollow(series.id) })
    }
    ExpandedBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            SheetHeader(MISSING_EXTENSIONS_TITLE, closeDescription = "Close", onDismiss = onDismiss) {
                TextButton(onClick = onOpenRepos) { Text(REPOS) }
            }
            val listing = state.listing
            if (listing == null) return@Column LoadingState()
            val groups = missingExtensions(missing, listing.extensions)
            if (listing.problems.isNotEmpty()) {
                FailureRow({ extensions.reloadListing(refresh = true) }, message = unreadReposLine(listing.problems.size))
            }
            if (groups.offered.isNotEmpty()) {
                SettingsGroup(IN_YOUR_REPOS) {
                    groups.offered.forEachIndexed { index, provider ->
                        if (index > 0) SettingsRowDivider()
                        val key = "${provider.extension.repo}/${provider.extension.packageName}"
                        val open = key in expanded
                        RepoExtensionRow(
                            provider.extension,
                            state,
                            summary = listOfNotNull("${provider.series.size} series", repoExtensionStatus(provider.extension))
                                .joinToString(" · "),
                            onInstall = extensions::install,
                            onClick = { expanded = if (open) expanded - key else expanded + key },
                            after = { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null) },
                        ) { ExtensionIcon(provider.extension.iconUrl) }
                        if (open) provider.series.forEach { title(it, true) }
                    }
                }
            }
            if (groups.unlisted.isNotEmpty()) {
                SettingsGroup(if (listing.problems.isEmpty()) NOT_IN_YOUR_REPOS else NOT_FOUND) {
                    groups.unlisted.forEachIndexed { index, series ->
                        if (index > 0) SettingsRowDivider()
                        title(series, false)
                    }
                }
            }
        }
    }
}

@Composable
private fun MissingTitleRow(series: Series, nested: Boolean, canSwitch: Boolean, onSwitch: () -> Unit, onUnfollow: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (nested) 64.dp else 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(series.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (canSwitch) {
                    DropdownMenuItem(text = { Text(SWITCH_SOURCE) }, onClick = {
                        menu = false
                        onSwitch()
                    })
                }
                DropdownMenuItem(text = { Text(UNFOLLOW) }, onClick = {
                    menu = false
                    onUnfollow()
                })
            }
        }
    }
}

internal fun unreadReposLine(count: Int): String = if (count == 1) "Couldn't read 1 repo" else "Couldn't read $count repos"

internal const val MISSING_EXTENSIONS_TITLE = "Missing extensions"
internal const val IN_YOUR_REPOS = "In your repos"
internal const val NOT_IN_YOUR_REPOS = "Not in your repos"
internal const val REPOS = "Repos"
internal const val NOT_FOUND = "Not found"
internal const val UNFOLLOW = "Unfollow"
internal const val SHOW_MISSING = "Show"
private const val MISSING_EXTENSIONS_KEY = "missing-extensions"
private const val SWITCH_SOURCE_KEY = "missing-extensions-switch-"
