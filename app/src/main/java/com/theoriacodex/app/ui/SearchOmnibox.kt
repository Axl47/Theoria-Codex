package com.theoriacodex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.theoriacodex.app.codex.codexBelongsToProfile
import com.theoriacodex.app.codex.markSavedSearchesSeen
import com.theoriacodex.app.recents.recentSearchPresentation
import com.theoriacodex.app.search.omnibox.OmniboxEntry
import com.theoriacodex.app.search.omnibox.OmniboxKind
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.settings.SettingsPage
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.navigation.LocalHiddenTagsOpener
import com.theoriacodex.app.ui.routes.BrowsingDestinationState
import com.theoriacodex.app.ui.routes.rememberRetained
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SavedSearchEntry
import kotlinx.coroutines.launch

/** A preference the one box switches in place: how it reads from settings and the Settings action that writes it. */
internal class SearchToggleCommand(
    val title: String,
    val keywords: List<String>,
    val read: (AppSettings) -> Boolean,
    val write: (Boolean) -> SettingsAction,
)

/** The Playback & downloads and Controls switches worth flipping without leaving Search; titles match Settings. */
internal val SEARCH_TOGGLE_COMMANDS = listOf(
    SearchToggleCommand("Instant Seek", listOf("scrub", "seek"), { it.viewer.instantSeek }, SettingsAction::SetInstantSeek),
    SearchToggleCommand("Auto-Rotate Videos", listOf("rotate", "orientation"), { it.viewer.autoRotateVideos }, SettingsAction::SetAutoRotateVideos),
    SearchToggleCommand(
        "Invert Scroll Direction", listOf("reverse", "swipe"),
        { it.viewer.invertMultiImageScrollDirection }, SettingsAction::SetInvertScrollDirection,
    ),
    SearchToggleCommand("Old Search Bar", listOf("pin search bar"), { it.oldSearchBar }, SettingsAction::SetOldSearchBar),
    SearchToggleCommand(
        "Download over metered networks", listOf("mobile data", "metered"),
        { it.cache.downloadsOverMetered }, SettingsAction::SetDownloadsOverMetered,
    ),
    SearchToggleCommand("Download while roaming", listOf("roaming"), { it.cache.downloadsOverRoaming }, SettingsAction::SetDownloadsOverRoaming),
)

/** The toggle commands as they stand in [settings]; switching one sends its Settings action to [dispatch]. */
internal fun searchToggleEntries(settings: AppSettings, dispatch: (SettingsAction) -> Unit): List<OmniboxEntry.Toggle> =
    SEARCH_TOGGLE_COMMANDS.map { command ->
        OmniboxEntry.Toggle(command.title, command.read(settings), command.keywords) { checked -> dispatch(command.write(checked)) }
    }

/** Settings pages the one box opens; titles match their Settings rows. */
private enum class SettingsPageCommand(val title: String, val keywords: List<String>, val page: SettingsPage) {
    SITES("Sites", listOf("sources", "accounts", "sign in", "log in"), SettingsPage.SITES),
    STORAGE("Storage & caching", listOf("cache", "space", "clear"), SettingsPage.STORAGE),
    STATS("Your stats", listOf("stats", "statistics", "usage"), SettingsPage.STATS),
    DIAGNOSTICS("Diagnostics report", listOf("diagnostics", "logs", "errors"), SettingsPage.DIAGNOSTICS),
}

/**
 * Everything besides tags that Search's composer matches typed text against: the active profile's Codices,
 * followed creators, saved and recent searches, and commands, each bound to what picking it does.
 */
@Composable
internal fun rememberSearchOmniboxEntries(shell: TheoriaShell, state: BrowsingDestinationState): List<OmniboxEntry> {
    val codices = rememberRetained("omnibox-codices") { shell.data.codexRepository.observeCodices() }.value.orEmpty()
    val recents = rememberRetained("omnibox-recent-searches") { shell.data.recentsRepository.observeSearches() }.value.orEmpty()
    val saved = shell.savedSearches
    val profileId = state.activeProfile.profileId
    val hiddenTags = LocalHiddenTagsOpener.current
    return remember(shell, codices, recents, saved, state.settings, profileId, hiddenTags) {
        buildList {
            codices.filter { codexBelongsToProfile(it.codexId, profileId) }.mapTo(this) { codex ->
                OmniboxEntry.Action(OmniboxKind.CODEX, codex.name) { shell.navController.navigate(AppRoute.codexDetail(codex.codexId)) }
            }
            state.settings.followedCreators.mapTo(this) { follow ->
                val creator = follow.creator
                OmniboxEntry.Action(OmniboxKind.CREATOR, creator.displayName, creator.source.displayName()) {
                    shell.scope.launch { shell.openCreatorProfile(creator) }
                }
            }
            saved.mapTo(this) { entry ->
                OmniboxEntry.Action(OmniboxKind.SAVED_SEARCH, entry.name, recentSearchPresentation(entry.search).title) {
                    shell.openSavedSearch(entry)
                }
            }
            recents.mapTo(this) { entry ->
                val presentation = recentSearchPresentation(entry)
                OmniboxEntry.Action(OmniboxKind.RECENT_SEARCH, presentation.title, presentation.subtitle) { shell.openSearchEntry(entry) }
            }
            addAll(searchToggleEntries(state.settings, shell.owners.settings::onAction))
            addAll(destinationCommands(shell))
            add(OmniboxEntry.Action(OmniboxKind.COMMAND, "Hidden tags", keywords = listOf("blacklist", "blocked", "mute")) {
                hiddenTags.openHiddenTags()
            })
        }
    }
}

private fun destinationCommands(shell: TheoriaShell): List<OmniboxEntry> {
    val pages = SettingsPageCommand.entries.filter { it != SettingsPageCommand.DIAGNOSTICS || shell.diagnosticsEntry != null }
    return pages.map { command ->
        OmniboxEntry.Action(OmniboxKind.COMMAND, command.title, keywords = command.keywords) { shell.openSettings(command.page) }
    } + OmniboxEntry.Action(OmniboxKind.COMMAND, "What's new", keywords = listOf("changelog", "release notes", "version")) {
        shell.owners.settings.onAction(SettingsAction.OpenChangelog)
    }
}

/** Runs a saved or recent search where it belongs: For You searches in For You, the rest in Search. */
internal fun TheoriaShell.openSearchEntry(entry: RecentSearchEntry) {
    if (entry.kind == RecentSearchKind.FYP) openHistoricalForYouSearch(entry) else openHistoricalSearch(entry)
}

/** Opens a saved search from Search, which shows its latest posts, so its "new posts" count is read. */
internal fun TheoriaShell.openSavedSearch(entry: SavedSearchEntry) {
    openSearchEntry(entry.search)
    scope.launch { data.uiRestoreRepository.markSavedSearchesSeen(setOf(entry.id)) }
}
