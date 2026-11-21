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
import com.theoriacodex.app.ui.navigation.HiddenTagsOpener
import com.theoriacodex.app.ui.navigation.LocalHiddenTagsOpener
import com.theoriacodex.app.ui.routes.BrowsingDestinationState
import com.theoriacodex.app.ui.routes.rememberRetained
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.SettingDestinations
import com.theoriacodex.data.repository.SettingToggles
import kotlinx.coroutines.launch

/** Every setting switch as it stands in [settings]; switching one sends its Settings action to [dispatch]. */
internal fun searchToggleEntries(settings: AppSettings, dispatch: (SettingsAction) -> Unit): List<OmniboxEntry.Toggle> =
    SettingToggles.all.map { toggle ->
        OmniboxEntry.Toggle(toggle.title, toggle.read(settings), toggle.keywords) { checked -> dispatch(SettingsAction.SetToggle(toggle, checked)) }
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
            addAll(destinationCommands(shell, hiddenTags))
        }
    }
}

/** The setting destinations the one box opens, each bound to what opens it here. */
private fun destinationCommands(shell: TheoriaShell, hiddenTags: HiddenTagsOpener): List<OmniboxEntry> = listOfNotNull(
    SettingDestinations.Sites to { shell.openSettings(SettingsPage.SITES) },
    SettingDestinations.HiddenTags to hiddenTags::openHiddenTags,
    SettingDestinations.Storage to { shell.openSettings(SettingsPage.STORAGE) },
    SettingDestinations.Stats to { shell.openSettings(SettingsPage.STATS) },
    (SettingDestinations.Diagnostics to { shell.openSettings(SettingsPage.DIAGNOSTICS) }).takeIf { shell.diagnosticsEntry != null },
    SettingDestinations.WhatsNew to { shell.owners.settings.onAction(SettingsAction.OpenChangelog) },
).map { (destination, open) ->
    OmniboxEntry.Action(OmniboxKind.COMMAND, destination.title, keywords = destination.keywords, run = open)
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
