package com.theoriacodex.app.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.LoadingState
import com.theoriacodex.app.ui.components.SettingsGroup
import com.theoriacodex.app.ui.components.SettingsHeader
import com.theoriacodex.app.ui.components.SettingsPageColumn
import com.theoriacodex.app.ui.components.SettingsRow
import com.theoriacodex.app.ui.components.SettingsRowDivider
import com.theoriacodex.app.ui.components.SingleChoiceMenu
import com.theoriacodex.app.ui.components.focusOnOpen
import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.languageName

internal const val REPOS_TITLE = "Repos"
internal const val BROWSE_TITLE = "Get extensions"
internal const val ADD_REPO_LABEL = "Add repo"

/** The user's repos, each with why it could not be read when that is so, the way to add one, and once there is one, Get extensions. */
@Composable
internal fun ReposPage(state: ExtensionsState, viewModel: ExtensionsViewModel, onBack: () -> Unit) {
    SettingsPageColumn {
        SettingsHeader(REPOS_TITLE, onBack, parents = listOf(EXTENSIONS_TITLE))
        val repos = state.repos
        if (repos == null) {
            LoadingState()
        } else if (repos.isNotEmpty()) {
            SettingsGroup(null) {
                repos.forEachIndexed { index, repo ->
                    if (index > 0) SettingsRowDivider()
                    val problem = state.listing?.problems?.get(repo.url)
                    SettingsRow(
                        title = repo.name,
                        summary = problem ?: repo.url,
                        summaryIsError = problem != null,
                        trailing = { TextButton(onClick = { viewModel.removeRepo(repo.url) }) { Text("Remove") } },
                    )
                }
            }
        }
        SettingsGroup(null) {
            // Leads on from a repo just added to what it offers.
            if (!repos.isNullOrEmpty()) {
                SettingsRow(BROWSE_TITLE, onClick = { viewModel.openSubpage(ExtensionsSubpage.BROWSE) })
                SettingsRowDivider()
            }
            SettingsRow(ADD_REPO_LABEL, onClick = { viewModel.askForRepo(true) })
        }
    }
    state.addRepo?.let { ask -> AddRepoDialog(ask, onDismiss = { viewModel.askForRepo(false) }, onAdd = viewModel::addRepo) }
}

/** Asks for a repo's address. Adding one is what lets its extensions be installed, so the dialog says what they can do. */
@Composable
private fun AddRepoDialog(ask: AddRepo, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    val submit = { if (url.isNotBlank() && !ask.busy) onAdd(url.trim()) }
    AlertDialog(
        // Stays open while the repo is read, so its answer lands in this dialog.
        onDismissRequest = { if (!ask.busy) onDismiss() },
        title = { Text(ADD_REPO_LABEL) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().focusOnOpen(),
                    value = url,
                    onValueChange = { url = it.replace("\n", "") },
                    label = { Text("Repo URL") },
                    isError = ask.problem != null,
                    supportingText = ask.problem?.let { { Text(it) } },
                    singleLine = true,
                    enabled = !ask.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                Text("Extensions you install from it run inside this app and can read its data.", style = MaterialTheme.typography.bodyMedium)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !ask.busy) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = submit, enabled = url.isNotBlank() && !ask.busy) { Text("Add") } },
    )
}

/**
 * Every extension the repos offer that this app can run, narrowed by kind when both are offered and by language (by
 * default the device's languages), then by name with a search.
 */
@Composable
internal fun BrowsePage(state: ExtensionsState, viewModel: ExtensionsViewModel, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<SeriesKind?>(null) }
    var language by rememberSaveable(stateSaver = LanguageFilter.Saver) { mutableStateOf<LanguageFilter>(LanguageFilter.Yours) }
    val deviceLanguages = LocalConfiguration.current.locales.toLanguageTags().split(',')
    val listing = state.listing
    val bothKinds = listing?.extensions.orEmpty().map { it.kind }.distinct().size > 1
    val shownKind = kind.takeIf { bothKinds }
    val rows = remember(listing, query, deviceLanguages, shownKind, language) {
        listing?.extensions?.let { browseRows(it, query, deviceLanguages, language, shownKind) }
    }
    val languages = remember(listing) { listing?.extensions?.let(::offeredLanguages).orEmpty() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 800.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") { SettingsHeader(BROWSE_TITLE, onBack, parents = listOf(EXTENSIONS_TITLE)) }
            item(key = "search") {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = query,
                    onValueChange = { query = it.replace("\n", "") },
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
            if (listing != null) {
                item(key = "filters") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (bothKinds) KindFilter(shownKind, onChoose = { kind = it })
                        LanguageChip(language, languages) { language = it }
                    }
                }
            }
            when {
                rows == null -> item(key = "loading") { LoadingState() }
                rows.isEmpty() -> item(key = "empty") { EmptyState(if (query.isBlank()) "No extensions" else "No matches") }
                else -> items(rows, key = { "${it.repo}/${it.packageName}" }) { extension ->
                    Card(Modifier.fillMaxWidth()) {
                        RepoExtensionRow(extension, state, bothKinds && shownKind == null, onInstall = viewModel::install)
                    }
                }
            }
        }
    }
}

/** Which languages Get extensions lists: the device's, every one, or [Only] one (with those covering every language). */
internal sealed interface LanguageFilter {
    data object Yours : LanguageFilter
    data object Every : LanguageFilter
    data class Only(val code: String) : LanguageFilter

    companion object {
        val Saver = androidx.compose.runtime.saveable.Saver<LanguageFilter, String>(
            save = { filter -> when (filter) { Yours -> ""; Every -> "*"; is Only -> filter.code } },
            restore = { saved -> when (saved) { "" -> Yours; "*" -> Every; else -> Only(saved) } },
        )
    }
}

/**
 * What the browse page lists: those of [kind] (any when null) in the [language] chosen, then name matches for [query].
 * Extensions covering every language ("all") show under any language.
 */
internal fun browseRows(
    extensions: List<RepoExtension>,
    query: String,
    deviceLanguages: List<String>,
    language: LanguageFilter = LanguageFilter.Yours,
    kind: SeriesKind? = null,
): List<RepoExtension> {
    val search = query.trim()
    val device = deviceLanguages.mapTo(HashSet()) { it.substringBefore('-').lowercase() } + "all"
    fun RepoExtension.inLanguage(): Boolean = when (language) {
        LanguageFilter.Every -> true
        LanguageFilter.Yours -> languages.any { it.substringBefore('-').lowercase() in device }
        is LanguageFilter.Only -> language.code in languages || "all" in languages
    }
    return extensions
        .filter { (kind == null || it.kind == kind) && it.inLanguage() && (search.isEmpty() || it.name.contains(search, ignoreCase = true)) }
        .sortedBy { it.name.lowercase() }
}

/** Every language some extension offers, by name; "all" is left out, since those extensions show under each, as are blank codes. */
internal fun offeredLanguages(extensions: List<RepoExtension>): List<String> =
    extensions.flatMap { it.languages }.distinct().filter { it.isNotBlank() && it != "all" }.sortedBy { languageName(it).lowercase() }

/** The chosen language as a chip; tapping it offers the device's languages, every language, or any one offered. */
@Composable
private fun LanguageChip(chosen: LanguageFilter, languages: List<String>, onChoose: (LanguageFilter) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun label(filter: LanguageFilter) = when (filter) {
        LanguageFilter.Yours -> "Your languages"
        LanguageFilter.Every -> "All languages"
        is LanguageFilter.Only -> languageName(filter.code)
    }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(label(chosen)) },
            leadingIcon = { Icon(Icons.Default.Language, contentDescription = null, Modifier.size(18.dp)) },
        )
        SingleChoiceMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            options = listOf(LanguageFilter.Yours, LanguageFilter.Every) + languages.map(LanguageFilter::Only),
            selected = chosen,
            label = ::label,
            onSelect = onChoose,
        )
    }
}

/** The Updates group atop the Extensions list: each extension a repo has a newer version of, with a way to update all. */
@Composable
internal fun UpdatesGroup(state: ExtensionsState, viewModel: ExtensionsViewModel) {
    val updates = state.updates
    if (updates.isEmpty()) return
    val action: (@Composable () -> Unit)? = if (updates.size > 1) ({ TextButton(onClick = viewModel::updateAll) { Text("Update all") } }) else null
    SettingsGroup("Updates", action) {
        updates.forEachIndexed { index, extension ->
            if (index > 0) SettingsRowDivider()
            RepoExtensionRow(extension, state, bothKinds = false, onInstall = viewModel::install)
        }
    }
}

/** One extension a repo offers: its languages and version, and Install or Update while that is possible. */
@Composable
private fun RepoExtensionRow(extension: RepoExtension, state: ExtensionsState, bothKinds: Boolean, onInstall: (RepoExtension) -> Unit) {
    val failure = state.failures[extension.packageName]
    SettingsRow(
        title = extension.name,
        summary = failure ?: repoExtensionSummary(extension, bothKinds),
        summaryIsError = failure != null,
        trailing = {
            when {
                extension.packageName in state.installing -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                extension.status == RepoExtension.Status.AVAILABLE -> TextButton(onClick = { onInstall(extension) }) { Text("Install") }
                extension.status == RepoExtension.Status.UPDATE -> TextButton(onClick = { onInstall(extension) }) { Text("Update") }
                else -> Unit
            }
        },
    )
}

/** Its languages (or how many), its version, what it is when both kinds are listed, and why it cannot be installed. */
internal fun repoExtensionSummary(extension: RepoExtension, bothKinds: Boolean): String {
    val languages = extension.languages.singleOrNull()?.let(::languageName) ?: "${extension.languages.size} languages"
    val status = when (extension.status) {
        RepoExtension.Status.INSTALLED -> "Installed"
        RepoExtension.Status.OTHER_KEY -> "Installed copy is signed with another key"
        else -> null
    }
    return listOfNotNull(extension.kind.label.takeIf { bothKinds }, languages, extension.versionName, status).joinToString(" · ")
}
