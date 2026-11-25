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
import androidx.compose.material.icons.filled.Settings
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
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.RepoListing
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.languageName
import com.theoriacodex.domain.series.seriesKind
import java.text.DateFormat
import kotlinx.coroutines.flow.first

class MissingExtensionsActions(
    val missing: () -> MissingSeriesViewModel,
    val switchSource: (SeriesId) -> SwitchSourceViewModel,
    val onOpenSeries: (SeriesId) -> Unit,
)

internal class MissingSheetRequest(val kind: SeriesKind, val onOpenRepos: () -> Unit, val onOpenExtension: (String) -> Unit, val onDismiss: () -> Unit)

@Composable
internal fun MissingExtensionsOverlay(
    request: MissingSheetRequest,
    missing: MissingSeriesViewModel,
    extensions: ExtensionsViewModel,
    actions: MissingExtensionsActions,
) {
    val state by missing.state.collectAsStateWithLifecycle()
    val shown = state?.series.orEmpty().filter { it.series.id.source.seriesKind == request.kind }
    val resolved = state != null && shown.isEmpty()
    LaunchedEffect(resolved) { if (resolved) request.onDismiss() }
    var resumed by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(extensions) {
        if (resumed) extensions.reloadListing()
        resumed = true
        onPauseOrDispose {}
    }
    var switching by remember { mutableStateOf<Series?>(null) }
    val from = switching
    if (from == null) {
        val switchable = state?.switchable.orEmpty()
        val dates = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
        val progress = shown.associateBy { it.series.id }
        MissingExtensionsSheet(
            missing = shown.map { it.series },
            extensions = extensions,
            titles = TitleActions({ it.id.source.seriesKind in switchable }, { switching = it }, missing::unfollow) { series ->
                progress[series.id]?.let { readingSummary(it, dates) }
            },
            request = request,
        )
    } else {
        SwitchSourceFor(from, actions.switchSource, onOpenSeries = { id ->
            request.onDismiss()
            actions.onOpenSeries(id)
        }) { switching = null }
    }
}

@Composable
private fun MissingExtensionsSheet(missing: List<Series>, extensions: ExtensionsViewModel, titles: TitleActions, request: MissingSheetRequest) {
    val state by extensions.state.collectAsStateWithLifecycle()
    val provider = ProviderActions(extensions::install, extensions::ask, { copy, languages -> languages.forEach { extensions.setLanguage(copy.id, it, true) } }, request.onOpenExtension)
    ExpandedBottomSheet(onDismiss = request.onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            SheetHeader(MISSING_EXTENSIONS_TITLE, closeDescription = "Close", onDismiss = request.onDismiss) {
                TextButton(onClick = request.onOpenRepos) { Text(REPOS) }
            }
            val listing = state.listing
            if (listing == null) LoadingState() else MissingGroups(missing, listing, state, extensions, provider, titles)
        }
    }
}

@Composable
private fun MissingGroups(
    missing: List<Series>,
    listing: RepoListing,
    state: ExtensionsState,
    extensions: ExtensionsViewModel,
    provider: ProviderActions,
    actions: TitleActions,
) {
    val groups = missingExtensions(missing, listing.extensions)
    if (listing.problems.isNotEmpty()) {
        FailureRow({ extensions.reloadListing(refresh = true) }, message = unreadReposLine(listing.problems.size))
    }
    if (groups.offered.isNotEmpty()) OfferedGroup(groups.offered, state, provider, actions)
    if (groups.unlisted.isNotEmpty()) UnlistedGroup(groups.unlisted, if (listing.problems.isEmpty()) NOT_IN_YOUR_REPOS else NOT_FOUND, actions)
}

@Composable
private fun UnlistedGroup(unlisted: List<Series>, title: String, actions: TitleActions) {
    val sites = unlisted.groupBy { siteHint(it.coverUrl) }.entries.sortedWith(compareBy(nullsLast()) { it.key })
    SettingsGroup(title) {
        sites.forEachIndexed { index, (site, series) ->
            if (index > 0) SettingsRowDivider()
            if (site != null) {
                Text(
                    site,
                    Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            series.forEach { FollowedTitleRow(it, nested = false, actions) }
        }
    }
}

@Composable
private fun OfferedGroup(providers: List<MissingProvider>, state: ExtensionsState, handlers: ProviderActions, actions: TitleActions) {
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    SettingsGroup(IN_YOUR_REPOS) {
        providers.forEachIndexed { index, provider ->
            if (index > 0) SettingsRowDivider()
            val key = "${provider.extension.repo}/${provider.extension.packageName}"
            ProviderRow(provider, state, open = key in expanded, onToggle = { expanded = if (key in expanded) expanded - key else expanded + key }, handlers, actions)
        }
    }
}

private class ProviderActions(
    val onInstall: (RepoExtension) -> Unit,
    val onTurnOn: (InstalledExtension) -> Unit,
    val onTurnOnLanguages: (InstalledExtension, List<String>) -> Unit,
    val onOpenExtension: (String) -> Unit,
)

@Composable
private fun ProviderRow(provider: MissingProvider, state: ExtensionsState, open: Boolean, onToggle: () -> Unit, handlers: ProviderActions, actions: TitleActions) {
    val extension = provider.extension
    val copy = installedCopy(extension, state.extensions)
    val gap = copy?.let { installedGap(it, provider.series, state.sourcesOff) }
    val status = when (gap) {
        null -> repoExtensionStatus(extension)
        InstalledGap.Off -> OFF
        is InstalledGap.LanguagesOff -> languagesOffLine(gap.languages)
        InstalledGap.NotLoaded -> COULDNT_LOAD
        InstalledGap.NotInVersion -> NOT_IN_THIS_VERSION
    }
    RepoExtensionRow(
        extension,
        state,
        summary = listOfNotNull("${provider.series.size} series", status).joinToString(" · "),
        onInstall = handlers.onInstall,
        onClick = onToggle,
        after = {
            if (copy != null) GapAction(copy, gap, handlers)
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        },
    ) { ExtensionIcon(extension.iconUrl) }
    if (open) provider.series.forEach { FollowedTitleRow(it, nested = true, actions) }
}

@Composable
private fun GapAction(copy: InstalledExtension, gap: InstalledGap?, handlers: ProviderActions) {
    when (gap) {
        InstalledGap.Off -> TextButton(onClick = { handlers.onTurnOn(copy) }) { Text(TURN_ON) }
        is InstalledGap.LanguagesOff -> TextButton(onClick = { handlers.onTurnOnLanguages(copy, gap.languages) }) { Text(TURN_ON) }
        InstalledGap.NotLoaded -> if (copy.hasPage) {
            IconButton(onClick = { handlers.onOpenExtension(copy.id) }) { Icon(Icons.Default.Settings, contentDescription = "${copy.name} settings") }
        }
        InstalledGap.NotInVersion, null -> Unit
    }
}

internal fun languagesOffLine(languages: List<String>): String = "${languages.joinToString { languageName(it) }} off"

internal fun unreadReposLine(count: Int): String = if (count == 1) "Couldn't read 1 repo" else "Couldn't read $count repos"

internal const val MISSING_EXTENSIONS_TITLE = "Missing extensions"
internal const val IN_YOUR_REPOS = "In your repos"
internal const val NOT_IN_YOUR_REPOS = "Not in your repos"
internal const val REPOS = "Repos"
internal const val NOT_FOUND = "Not found"
internal const val TURN_ON = "Turn on"
internal const val OFF = "Off"
internal const val COULDNT_LOAD = "Couldn't load"
internal const val NOT_IN_THIS_VERSION = "Not in this version"
