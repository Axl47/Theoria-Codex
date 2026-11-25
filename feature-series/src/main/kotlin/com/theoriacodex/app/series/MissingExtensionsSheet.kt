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
import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.RepoListing
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

private class TitleActions(val canSwitch: (Series) -> Boolean, val onSwitch: (Series) -> Unit, val onUnfollow: (SeriesId) -> Unit)

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
    val actions = TitleActions(canSwitch, onSwitch, onUnfollow)
    ExpandedBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            SheetHeader(MISSING_EXTENSIONS_TITLE, closeDescription = "Close", onDismiss = onDismiss) {
                TextButton(onClick = onOpenRepos) { Text(REPOS) }
            }
            val listing = state.listing
            if (listing == null) LoadingState() else MissingGroups(missing, listing, state, extensions, actions)
        }
    }
}

@Composable
private fun MissingGroups(missing: List<Series>, listing: RepoListing, state: ExtensionsState, extensions: ExtensionsViewModel, actions: TitleActions) {
    val groups = missingExtensions(missing, listing.extensions)
    if (listing.problems.isNotEmpty()) {
        FailureRow({ extensions.reloadListing(refresh = true) }, message = unreadReposLine(listing.problems.size))
    }
    if (groups.offered.isNotEmpty()) OfferedGroup(groups.offered, state, extensions::install, actions)
    if (groups.unlisted.isNotEmpty()) UnlistedGroup(groups.unlisted, if (listing.problems.isEmpty()) NOT_IN_YOUR_REPOS else NOT_FOUND, actions)
}

@Composable
private fun UnlistedGroup(unlisted: List<Series>, title: String, actions: TitleActions) {
    SettingsGroup(title) {
        unlisted.forEachIndexed { index, series ->
            if (index > 0) SettingsRowDivider()
            MissingTitleRow(series, nested = false, actions)
        }
    }
}

@Composable
private fun OfferedGroup(providers: List<MissingProvider>, state: ExtensionsState, onInstall: (RepoExtension) -> Unit, actions: TitleActions) {
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    SettingsGroup(IN_YOUR_REPOS) {
        providers.forEachIndexed { index, provider ->
            if (index > 0) SettingsRowDivider()
            val key = "${provider.extension.repo}/${provider.extension.packageName}"
            ProviderRow(provider, state, open = key in expanded, onToggle = { expanded = if (key in expanded) expanded - key else expanded + key }, onInstall, actions)
        }
    }
}

@Composable
private fun ProviderRow(provider: MissingProvider, state: ExtensionsState, open: Boolean, onToggle: () -> Unit, onInstall: (RepoExtension) -> Unit, actions: TitleActions) {
    val extension = provider.extension
    RepoExtensionRow(
        extension,
        state,
        summary = listOfNotNull("${provider.series.size} series", repoExtensionStatus(extension)).joinToString(" · "),
        onInstall = onInstall,
        onClick = onToggle,
        after = { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null) },
    ) { ExtensionIcon(extension.iconUrl) }
    if (open) provider.series.forEach { MissingTitleRow(it, nested = true, actions) }
}

@Composable
private fun MissingTitleRow(series: Series, nested: Boolean, actions: TitleActions) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (nested) 64.dp else 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(series.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (actions.canSwitch(series)) {
                    DropdownMenuItem(text = { Text(SWITCH_SOURCE) }, onClick = {
                        menu = false
                        actions.onSwitch(series)
                    })
                }
                DropdownMenuItem(text = { Text(UNFOLLOW) }, onClick = {
                    menu = false
                    actions.onUnfollow(series.id)
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
