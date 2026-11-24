package com.theoriacodex.app.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import com.theoriacodex.app.ui.components.focusOnOpen
import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.languageName

internal const val REPOS_TITLE = "Repos"
internal const val BROWSE_TITLE = "Get extensions"

/** The user's repos, each with why it could not be read when that is so, and the way to add one. */
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
        SettingsGroup(null) { SettingsRow("Add repo", onClick = { viewModel.askForRepo(true) }) }
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
        title = { Text("Add repo") },
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
 * Every extension the repos offer that this app can run. Without a search it shows those for the device's languages
 * and those covering every language; a search looks through all of them.
 */
@Composable
internal fun BrowsePage(state: ExtensionsState, viewModel: ExtensionsViewModel, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val deviceLanguages = LocalConfiguration.current.locales.toLanguageTags().split(',')
    val listing = state.listing
    val rows = remember(listing, query, deviceLanguages) { listing?.extensions?.let { browseRows(it, query, deviceLanguages) } }
    val bothKinds = listing?.extensions.orEmpty().map { it.kind }.distinct().size > 1
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
            when {
                rows == null -> item(key = "loading") { LoadingState() }
                rows.isEmpty() -> item(key = "empty") { EmptyState(if (query.isBlank()) "No extensions" else "No matches") }
                else -> items(rows, key = { "${it.repo}/${it.packageName}" }) { extension ->
                    Card(Modifier.fillMaxWidth()) {
                        RepoExtensionRow(extension, state, bothKinds, onInstall = viewModel::install)
                    }
                }
            }
        }
    }
}

/** What the browse page lists for [query]: name matches among all, or without one the device's languages and "all". */
internal fun browseRows(extensions: List<RepoExtension>, query: String, deviceLanguages: List<String>): List<RepoExtension> {
    val search = query.trim()
    val device = deviceLanguages.mapTo(HashSet()) { it.substringBefore('-').lowercase() } + "all"
    return extensions
        .filter { extension ->
            if (search.isNotEmpty()) extension.name.contains(search, ignoreCase = true)
            else extension.languages.any { it.substringBefore('-').lowercase() in device }
        }
        .sortedBy { it.name.lowercase() }
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
