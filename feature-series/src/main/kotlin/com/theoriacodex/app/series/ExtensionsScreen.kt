package com.theoriacodex.app.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.LoadingState
import com.theoriacodex.app.ui.components.SettingsGroup
import com.theoriacodex.app.ui.components.SettingsHeader
import com.theoriacodex.app.ui.components.SettingsPageColumn
import com.theoriacodex.app.ui.components.SettingsRow
import com.theoriacodex.app.ui.components.SettingsRowDivider
import com.theoriacodex.app.ui.components.SwitchRow
import com.theoriacodex.domain.series.ExtensionLanguage
import com.theoriacodex.domain.series.InstalledExtension
import java.util.Locale

internal const val EXTENSIONS_TITLE = "Extensions"

/**
 * Every extension on the device with its switch, and the page of one that is on: its languages and its own settings.
 * An extension runs only once it is switched on here.
 */
@Composable
fun ExtensionsScreen(viewModel: ExtensionsViewModel, onBack: () -> Unit) {
    val extensions by viewModel.state.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = extensions?.firstOrNull { it.id == openId && it.on }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    BackHandler(enabled = open != null) { openId = null }
    if (open != null) {
        ExtensionPage(open, viewModel, onBack = { openId = null })
    } else {
        ExtensionsList(extensions, onOpen = { openId = it }, onSetOn = viewModel::setOn, onBack = onBack)
    }
}

@Composable
private fun ExtensionsList(extensions: List<InstalledExtension>?, onOpen: (String) -> Unit, onSetOn: (String, Boolean) -> Unit, onBack: () -> Unit) {
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsPageColumn {
        SettingsHeader(EXTENSIONS_TITLE, onBack)
        when {
            extensions == null -> LoadingState()
            extensions.isEmpty() -> EmptyState("No extensions installed")
            else -> SettingsGroup(null) {
                extensions.forEachIndexed { index, extension ->
                    if (index > 0) SettingsRowDivider()
                    ExtensionRow(extension, onOpen, onSwitch = { on -> if (on) asking = extension.id else onSetOn(extension.id, false) })
                }
            }
        }
    }
    extensions?.firstOrNull { it.id == asking }?.let { extension ->
        TurnOnDialog(extension, onDismiss = { asking = null }) {
            asking = null
            onSetOn(extension.id, true)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn on ${extension.name}?") },
        text = { Text("Its code runs inside this app and can read this app's data.\n\nSigned by ${extension.signer}") },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Turn on") } },
    )
}

@Composable
private fun ExtensionPage(extension: InstalledExtension, viewModel: ExtensionsViewModel, onBack: () -> Unit) {
    SettingsPageColumn {
        SettingsHeader(extension.name, onBack, parents = listOf(EXTENSIONS_TITLE))
        SettingsGroup(null) { SettingsRow("Version ${extension.versionName}", extension.signer) }
        extension.settings.forEach { source ->
            // Settings come before the languages, which can run to dozens of rows. Each language is a source with
            // settings of its own, so with several on, each group says whose it is.
            SettingsGroup(if (extension.settings.size > 1) "${source.sourceName} · ${languageName(source.language)}" else "Settings") {
                ExtensionSettingRows(source.settings) { setting -> viewModel.change(extension.id, source.sourceId, setting) }
            }
        }
        if (extension.languages.size > 1) {
            SettingsGroup("Languages") {
                extension.languages.forEachIndexed { index, language ->
                    if (index > 0) SettingsRowDivider()
                    SwitchRow(languageName(language.code), language.on) { on -> viewModel.setLanguage(extension.id, language.code, on) }
                }
            }
        }
    }
}

/** Whether tapping the row leads anywhere: only an extension that is on has languages or settings to show. */
private val InstalledExtension.hasPage: Boolean get() = on && (languages.size > 1 || settings.isNotEmpty())

/**
 * Off, what there is to judge it by: its version and the start of its signer, which the question asked before it is
 * turned on shows whole. On, its version and what it offers.
 */
internal fun extensionSummary(extension: InstalledExtension): String = when {
    extension.problem != null -> listOf(extension.versionName, extension.problem).joinToString(SEPARATOR)
    !extension.on -> listOf(extension.versionName, extension.signer.orEmpty().take(SIGNER_START) + "…").joinToString(SEPARATOR)
    else -> (listOf(extension.versionName) + extension.languages.filter(ExtensionLanguage::on).map { languageName(it.code) }).joinToString(SEPARATOR)
}

/** A source language's name in the device's language; Mihon's two codes that are not languages get plain words. */
internal fun languageName(code: String): String = when (code) {
    "all" -> "All"
    "other" -> "Other"
    else -> Locale.forLanguageTag(code).getDisplayName(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }.ifBlank { code }
}

private const val SEPARATOR = " · "
private const val SIGNER_START = 16
