package com.theoriacodex.app.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.theoriacodex.domain.series.languageName

internal const val EXTENSIONS_TITLE = "Extensions"

/**
 * Every extension on the device with its switch, the updates the repos offer, and the page of one extension: its
 * languages and its own settings. An extension runs only once it is switched on here, or installed from a repo here.
 * The user's repos and what they offer are pages of their own.
 */
@Composable
fun ExtensionsScreen(viewModel: ExtensionsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val open = state.extensions?.firstOrNull { it.id == state.openId && it.hasPage }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    BackHandler(enabled = open != null || state.subpage != null) { if (open != null) viewModel.open(null) else viewModel.openSubpage(null) }
    when {
        open != null -> ExtensionPage(open, state.settings.orEmpty(), viewModel, onBack = { viewModel.open(null) })
        state.subpage == ExtensionsSubpage.REPOS -> ReposPage(state, viewModel, onBack = { viewModel.openSubpage(null) })
        state.subpage == ExtensionsSubpage.BROWSE -> BrowsePage(state, viewModel, onBack = { viewModel.openSubpage(null) })
        else -> ExtensionsList(state, viewModel, onBack = onBack)
    }
    state.asking?.let { extension -> TurnOnDialog(extension, onDismiss = { viewModel.ask(null) }) { viewModel.setOn(extension, true) } }
}

@Composable
private fun ExtensionsList(state: ExtensionsState, viewModel: ExtensionsViewModel, onBack: () -> Unit) {
    val extensions = state.extensions
    SettingsPageColumn {
        SettingsHeader(EXTENSIONS_TITLE, onBack)
        UpdatesGroup(state, viewModel)
        when {
            extensions == null -> LoadingState()
            // With no repo yet, the empty page offers the first step; once one exists, Get extensions is the row below.
            extensions.isEmpty() -> if (state.repos?.isEmpty() == true) {
                EmptyState("No extensions installed", actionLabel = ADD_REPO_LABEL) {
                    viewModel.openSubpage(ExtensionsSubpage.REPOS)
                    viewModel.askForRepo(true)
                }
            } else {
                EmptyState("No extensions installed")
            }
            else -> {
                // With extensions of both kinds installed, each kind is a group headed by its name.
                val kinds = extensions.groupBy(InstalledExtension::kind).toSortedMap()
                kinds.forEach { (kind, group) ->
                    ExtensionGroup(kind.label.takeIf { kinds.size > 1 }, group, viewModel::open, viewModel::ask) { viewModel.setOn(it, false) }
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
    onOpen: (String) -> Unit,
    onAsk: (InstalledExtension) -> Unit,
    onTurnOff: (InstalledExtension) -> Unit,
) {
    SettingsGroup(title) {
        extensions.forEachIndexed { index, extension ->
            if (index > 0) SettingsRowDivider()
            ExtensionRow(extension, onOpen, onSwitch = { on -> if (on) onAsk(extension) else onTurnOff(extension) })
        }
    }
}

@Composable
private fun ExtensionRow(extension: InstalledExtension, onOpen: (String) -> Unit, onSwitch: (Boolean) -> Unit) {
    SettingsRow(
        title = extension.name,
        summary = extensionSummary(extension),
        summaryIsError = extension.problem != null,
        onClick = if (extension.hasPage) ({ onOpen(extension.id) }) else null,
        trailing = {
            // The toggle is this box, not the Switch, so it carries the extension's name instead of being a bare switch.
            // One with no signing certificate can never be on; one that is on can always be turned off.
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

/** The one place a mistake is costly: what turning an extension on lets it do, and who signed it. */
@Composable
private fun TurnOnDialog(extension: InstalledExtension, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = "Turn on ${extension.name}?",
        text = "Its code runs inside this app and can read this app's data.\n\nSigned by ${extension.signer}",
        confirmLabel = "Turn on",
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
            // Settings come before the languages, which can run to dozens of rows. Each language is a source with
            // settings of its own, so with several on, each group says whose it is.
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

/**
 * Whether tapping the row leads anywhere: an extension that is on and loaded has languages and settings to show, and
 * one this app installed can be removed there.
 */
private val InstalledExtension.hasPage: Boolean get() = on && languages.isNotEmpty() || removable

/** Its version, then why it offers nothing or, once on, the languages it offers. Who signed it is in the question asked before it is turned on. */
internal fun extensionSummary(extension: InstalledExtension): String = when {
    extension.problem != null -> listOf(extension.versionName, extension.problem).joinToString(SEPARATOR)
    !extension.on -> extension.versionName
    else -> (listOf(extension.versionName) + extension.languages.filter(ExtensionLanguage::on).map { languageName(it.code) }).joinToString(SEPARATOR)
}

private const val SEPARATOR = " · "
