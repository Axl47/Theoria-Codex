package com.theoriacodex.app.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.ui.components.ConfirmDialog
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.LoadingState
import com.theoriacodex.app.ui.components.SettingsGroup
import com.theoriacodex.app.ui.components.SettingsHeader
import com.theoriacodex.app.ui.components.SettingsPageColumn
import com.theoriacodex.app.ui.components.SettingsRow
import com.theoriacodex.app.ui.components.SettingsRowDivider
import com.theoriacodex.app.ui.components.SwitchRow
import com.theoriacodex.domain.series.ExtensionLanguage
import com.theoriacodex.domain.series.ExtensionSourceSettings
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.languageName
import com.theoriacodex.domain.series.seriesKind

internal const val EXTENSIONS_TITLE = "Extensions"

@Composable
fun ExtensionsScreen(viewModel: ExtensionsViewModel, onBack: () -> Unit, missing: MissingExtensionsActions? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val open = state.extensions?.firstOrNull { it.id == state.openId && it.hasPage }
    val missingSeries = missing?.let { actions -> viewModel(key = MISSING_SERIES_KEY) { actions.missing() } }
    var missingKind by rememberSaveable { mutableStateOf<SeriesKind?>(null) }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val closeSubpage = { if (state.subpage == viewModel.entryPage) onBack() else viewModel.openSubpage(null) }
    BackHandler(enabled = open != null || state.subpage != null) { if (open != null) viewModel.open(null) else closeSubpage() }
    when {
        open != null -> ExtensionPage(open, state.settings.orEmpty(), viewModel, onBack = { viewModel.open(null) })
        state.subpage == ExtensionsSubpage.REPOS -> ReposPage(state, viewModel, onBack = closeSubpage)
        state.subpage == ExtensionsSubpage.BROWSE -> BrowsePage(state, viewModel, onBack = closeSubpage)
        else -> {
            val shown = missingSeries?.state?.collectAsStateWithLifecycle()?.value?.series.orEmpty().map { it.series }
            ExtensionsList(state, shown, viewModel, onShowMissing = { missingKind = it }, onBack = onBack)
        }
    }
    val kind = missingKind
    if (kind != null && missing != null && missingSeries != null) {
        val request = MissingSheetRequest(
            kind,
            onOpenRepos = {
                missingKind = null
                viewModel.openSubpage(ExtensionsSubpage.REPOS)
            },
            onOpenExtension = { id ->
                missingKind = null
                viewModel.open(id)
            },
            onDismiss = { missingKind = null },
        )
        MissingExtensionsOverlay(request, missingSeries, viewModel, missing)
    }
    state.asking?.let { extension -> TurnOnDialog(extension, onDismiss = { viewModel.ask(null) }) { viewModel.setOn(extension, true) } }
}

@Composable
private fun ExtensionsList(state: ExtensionsState, missing: List<Series>, viewModel: ExtensionsViewModel, onShowMissing: (SeriesKind) -> Unit, onBack: () -> Unit) {
    val extensions = state.extensions
    SettingsPageColumn {
        SettingsHeader(EXTENSIONS_TITLE, onBack)
        UpdatesGroup(state, viewModel)
        when {
            extensions == null -> LoadingState()
            extensions.isEmpty() && missing.isEmpty() -> if (state.repos?.isEmpty() == true) {
                EmptyState("No extensions installed", actionLabel = ADD_REPO_LABEL) {
                    viewModel.openSubpage(ExtensionsSubpage.REPOS)
                    viewModel.askForRepo(true)
                }
            } else {
                EmptyState("No extensions installed")
            }
            else -> {
                val installed = extensions.groupBy(InstalledExtension::kind)
                val absent = missing.groupBy { it.id.source.seriesKind }
                val kinds = (installed.keys + absent.keys).sorted()
                kinds.forEach { kind ->
                    ExtensionGroup(
                        title = kind.label.takeIf { kinds.size > 1 },
                        extensions = installed[kind].orEmpty(),
                        missing = absent[kind]?.size ?: 0,
                        onShowMissing = { onShowMissing(kind) },
                        onOpen = viewModel::open,
                        onAsk = viewModel::ask,
                    ) { viewModel.setOn(it, false) }
                }
            }
        }
        SettingsGroup(null) {
            if (!state.repos.isNullOrEmpty()) {
                SettingsRow(BROWSE_TITLE, onClick = { viewModel.openSubpage(ExtensionsSubpage.BROWSE) })
                SettingsRowDivider()
            }
            SettingsRow(REPOS_TITLE, summary = state.repos?.map { it.name }?.distinct()?.joinToString(), onClick = { viewModel.openSubpage(ExtensionsSubpage.REPOS) })
        }
    }
}

@Composable
private fun ExtensionGroup(
    title: String?,
    extensions: List<InstalledExtension>,
    missing: Int,
    onShowMissing: () -> Unit,
    onOpen: (String) -> Unit,
    onAsk: (InstalledExtension) -> Unit,
    onTurnOff: (InstalledExtension) -> Unit,
) {
    SettingsGroup(title) {
        if (missing > 0) MissingRow(missing, onShowMissing)
        extensions.forEachIndexed { index, extension ->
            if (index > 0 || missing > 0) SettingsRowDivider()
            ExtensionRow(extension, onOpen, onSwitch = { on -> if (on) onAsk(extension) else onTurnOff(extension) })
        }
    }
}

@Composable
private fun MissingRow(count: Int, onClick: () -> Unit) {
    SettingsRow(
        title = MISSING_EXTENSIONS_TITLE,
        summary = "$count series",
        summaryIsError = true,
        onClick = onClick,
        leading = {
            Box(Modifier.size(EXTENSION_ICON_SIZE), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun ExtensionRow(extension: InstalledExtension, onOpen: (String) -> Unit, onSwitch: (Boolean) -> Unit) {
    SettingsRow(
        title = extension.name,
        summary = extensionSummary(extension),
        summaryIsError = extension.problem != null,
        onClick = if (extension.hasPage) ({ onOpen(extension.id) }) else null,
        leading = { ExtensionIcon(extension.icon) },
        trailing = {
            val enabled = extension.signer != null
            Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .toggleable(value = extension.on, enabled = enabled, role = Role.Switch, onValueChange = onSwitch)
                    .semantics { contentDescription = extension.name },
            ) {
                Switch(checked = extension.on, onCheckedChange = null, enabled = enabled)
            }
        },
    )
}

@Composable
internal fun TurnOnDialog(extension: InstalledExtension, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = "Turn on ${extension.name}?",
        text = "Its code runs inside this app and can read this app's data.\n\nSigned by ${extension.signer}",
        confirmLabel = TURN_ON,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun ExtensionPage(extension: InstalledExtension, settings: List<ExtensionSourceSettings>, viewModel: ExtensionsViewModel, onBack: () -> Unit) {
    SettingsPageColumn {
        SettingsHeader(extension.name, onBack, parents = listOf(EXTENSIONS_TITLE))
        SettingsGroup(null) {
            SettingsRow(
                "Version ${extension.versionName}",
                extension.signer,
                trailing = if (extension.removable) ({ TextButton(onClick = { viewModel.uninstall(extension) }) { Text("Remove") } }) else null,
            )
        }
        settings.forEach { source ->
            SettingsGroup(if (settings.size > 1) "${source.sourceName} · ${languageName(source.language)}" else "Settings") {
                ExtensionSettingRows(source.settings) { setting -> viewModel.change(extension.id, source.sourceId, setting) }
            }
        }
        if (extension.languages.isNotEmpty()) {
            SettingsGroup("Languages") {
                extension.languages.forEachIndexed { index, language ->
                    if (index > 0) SettingsRowDivider()
                    SwitchRow(languageName(language.code), language.on) { on -> viewModel.setLanguage(extension.id, language.code, on) }
                }
            }
        }
    }
}

internal val InstalledExtension.hasPage: Boolean get() = on && languages.isNotEmpty() || removable

internal fun extensionSummary(extension: InstalledExtension): String = when {
    extension.problem != null -> listOf(extension.versionName, extension.problem).joinToString(SEPARATOR)
    !extension.on -> extension.versionName
    else -> (listOf(extension.versionName) + extension.languages.filter(ExtensionLanguage::on).map { languageName(it.code) }).joinToString(SEPARATOR)
}

private const val SEPARATOR = " · "
private const val MISSING_SERIES_KEY = "missing-series"
