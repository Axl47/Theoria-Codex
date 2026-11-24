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
import androidx.compose.foundation.lazy.LazyListScope
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
import com.theoriacodex.domain.series.languagePart

internal const val REPOS_TITLE = "Repos"
internal const val BROWSE_TITLE = "Get extensions"
internal const val ADD_REPO_LABEL = "Add repo"

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
            if (!repos.isNullOrEmpty()) {
                SettingsRow(BROWSE_TITLE, onClick = { viewModel.openSubpage(ExtensionsSubpage.BROWSE) })
                SettingsRowDivider()
            }
            SettingsRow(ADD_REPO_LABEL, onClick = { viewModel.askForRepo(true) })
        }
    }
    state.addRepo?.let { ask -> AddRepoDialog(ask, onDismiss = { viewModel.askForRepo(false) }, onAdd = viewModel::addRepo) }
}

@Composable
private fun AddRepoDialog(ask: AddRepo, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    val submit = { if (url.isNotBlank() && !ask.busy) onAdd(url.trim()) }
    AlertDialog(
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
    val elsewhere = remember(rows) { listing?.extensions?.let { otherLanguageMatches(it, rows, query, deviceLanguages, language, shownKind) } ?: 0 }
    val languages = remember(listing) { listing?.extensions?.let(::offeredLanguages).orEmpty() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 800.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") { SettingsHeader(BROWSE_TITLE, onBack, parents = listOf(EXTENSIONS_TITLE)) }
            item(key = "search") { BrowseSearchField(query) { query = it } }
            if (listing != null) {
                item(key = "filters") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (bothKinds) KindFilter(shownKind, onChoose = { kind = it })
                        LanguageChip(language, languages) { language = it }
                    }
                }
            }
            browseRowItems(rows, query, elsewhere, state, showKind = bothKinds && shownKind == null, onInstall = viewModel::install) {
                language = LanguageFilter.Every
            }
        }
    }
}

private fun LazyListScope.browseRowItems(
    rows: List<RepoExtension>?,
    query: String,
    elsewhere: Int,
    state: ExtensionsState,
    showKind: Boolean,
    onInstall: (RepoExtension) -> Unit,
    onShowAllLanguages: () -> Unit,
) {
    when {
        rows == null -> item(key = "loading") { LoadingState() }
        rows.isEmpty() -> item(key = "empty") {
            EmptyState(
                if (query.isBlank()) "No extensions" else "No matches",
                actionLabel = "Show $elsewhere in all languages".takeIf { elsewhere > 0 },
                onAction = onShowAllLanguages.takeIf { elsewhere > 0 },
            )
        }
        else -> items(rows, key = { "${it.repo}/${it.packageName}" }) { extension ->
            Card(Modifier.fillMaxWidth()) { RepoExtensionRow(extension, state, showKind, onInstall) }
        }
    }
}

@Composable
private fun BrowseSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = query,
        onValueChange = { onQueryChange(it.replace("\n", "")) },
        placeholder = { Text("Search") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

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

internal fun browseRows(
    extensions: List<RepoExtension>,
    query: String,
    deviceLanguages: List<String>,
    language: LanguageFilter = LanguageFilter.Yours,
    kind: SeriesKind? = null,
): List<RepoExtension> {
    val search = query.trim()
    val device = deviceLanguages.mapTo(HashSet(), ::languagePart) + "all"
    fun RepoExtension.inLanguage(): Boolean = when (language) {
        LanguageFilter.Every -> true
        LanguageFilter.Yours -> languages.any { languagePart(it) in device }
        is LanguageFilter.Only -> languages.any { languagePart(it) == language.code || it == "all" }
    }
    return extensions
        .filter { (kind == null || it.kind == kind) && it.inLanguage() && (search.isEmpty() || it.name.contains(search, ignoreCase = true)) }
        .sortedBy { it.name.lowercase() }
}

internal fun offeredLanguages(extensions: List<RepoExtension>): List<String> =
    extensions.flatMap { it.languages }.map(::languagePart).filter { it.isNotBlank() && it != "all" }.distinct()
        .sortedBy { languageName(it).lowercase() }

internal fun otherLanguageMatches(
    extensions: List<RepoExtension>,
    rows: List<RepoExtension>?,
    query: String,
    deviceLanguages: List<String>,
    language: LanguageFilter,
    kind: SeriesKind?,
): Int {
    if (rows?.isEmpty() != true || query.isBlank() || language == LanguageFilter.Every) return 0
    return browseRows(extensions, query, deviceLanguages, LanguageFilter.Every, kind).size
}

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

internal fun repoExtensionSummary(extension: RepoExtension, bothKinds: Boolean): String {
    val languages = extension.languages.singleOrNull()?.let(::languageName) ?: "${extension.languages.size} languages"
    val status = when (extension.status) {
        RepoExtension.Status.INSTALLED -> "Installed"
        RepoExtension.Status.OTHER_KEY -> "Installed copy is signed with another key"
        else -> null
    }
    return listOfNotNull(extension.kind.label.takeIf { bothKinds }, languages, extension.versionName, status).joinToString(" · ")
}
