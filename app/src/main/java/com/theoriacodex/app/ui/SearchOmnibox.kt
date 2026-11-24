package com.theoriacodex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.theoriacodex.app.codex.CodexCoverCandidate
import com.theoriacodex.app.codex.codexBelongsToProfile
import com.theoriacodex.app.codex.codexPostCount
import com.theoriacodex.app.codex.markSavedSearchesSeen
import com.theoriacodex.app.recents.recentSearchPresentation
import com.theoriacodex.app.search.omnibox.OmniboxEntry
import com.theoriacodex.app.search.omnibox.OmniboxKind
import com.theoriacodex.app.series.label
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.navigation.LocalHiddenTagsOpener
import com.theoriacodex.app.ui.routes.BrowsingDestinationState
import com.theoriacodex.app.ui.routes.rememberCodexCollection
import com.theoriacodex.app.ui.routes.rememberRetained
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.SettingChoices
import com.theoriacodex.data.repository.SettingToggles
import com.theoriacodex.data.repository.SettingDestinations
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesCovers
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.seriesKind
import kotlinx.coroutines.launch

/** Creators of recently watched posts the one box offers after the followed ones. */
private const val RECENT_CREATOR_LIMIT = 20

/**
 * Everything besides tags that Search's composer matches typed text against: the active profile's Codices, followed
 * series, followed creators and those of recently watched posts, saved and recent searches, and every setting
 * definition (see [SETTING_BINDINGS]), each bound to what picking it does.
 */
@Composable
internal fun rememberSearchOmniboxEntries(shell: TheoriaShell, state: BrowsingDestinationState): List<OmniboxEntry> {
    val profileId = state.activeProfile.profileId
    val allCodices = rememberRetained("omnibox-codices") { shell.data.codexRepository.observeCodices() }.value.orEmpty()
    val codices = remember(allCodices, profileId) { allCodices.filter { codexBelongsToProfile(it.codexId, profileId) } }
    val collection = rememberCodexCollection(shell.data)
    val recents = rememberRetained("omnibox-recent-searches") { shell.data.recentsRepository.observeSearches() }.value.orEmpty()
    val watched = rememberRetained("omnibox-watched-posts") { shell.data.recentsRepository.observeWatchedPosts() }.value.orEmpty()
    val saved = shell.savedSearches
    val hiddenTags = LocalHiddenTagsOpener.current
    val series = rememberFollowedSeriesEntries(shell)
    return remember(shell, codices, collection, series, recents, watched, saved, state.settings, profileId, hiddenTags) {
        buildList {
            codices.mapTo(this) { codex ->
                OmniboxEntry.Action(
                    OmniboxKind.CODEX,
                    codex.name,
                    detail = collection.itemCounts[codex.codexId]?.let(::codexPostCount),
                    cover = collection.coverCandidates[codex.codexId].orEmpty(),
                ) { shell.navController.navigate(AppRoute.codexDetail(codex.codexId)) }
            }
            addAll(series)
            val followed = state.settings.followedCreators.map { it.creator }
            (followed + recentCreators(watched, followed)).mapTo(this) { creator ->
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
                OmniboxEntry.Action(OmniboxKind.RECENT_SEARCH, presentation.title, presentation.subtitle) { shell.openHistoricalSearch(entry) }
            }
            val hidden = setOfNotNull(
                SettingDestinations.Diagnostics.id.takeIf { shell.diagnosticsEntry == null },
                SettingDestinations.Extensions.id.takeIf { shell.container.series == null },
                SettingChoices.SeriesUpdates.id.takeIf { shell.container.series == null },
                SettingToggles.SeriesDeleteAfterReading.id.takeIf { shell.container.series == null },
                SettingDestinations.PictureTags.id.takeIf { shell.container.pictures == null },
            )
            addAll(searchSettingEntries(state.settings, hidden, shell.owners.settings::onAction) { binding ->
                shell.openSettingBinding(binding, hiddenTags::openHiddenTags)
            })
        }
    }
}

/**
 * The followed series, read from the device alone, each opening its page. A row names its source, as the Series tab
 * does, once that source's extension has loaded, which only the extensions of followed series do; until then, or when
 * the source is not on or fails to load, it names only the kind.
 */
@Composable
private fun rememberFollowedSeriesEntries(shell: TheoriaShell): List<OmniboxEntry> {
    val dependencies = shell.container.series ?: return emptyList()
    val followed = rememberRetained("omnibox-followed-series") { dependencies.repository.observeFollowed() }.value.orEmpty()
    val keys = remember(followed) { followed.mapTo(LinkedHashSet()) { it.series.id.source } }
    val names by produceState(emptyMap<SourceKey, String>(), dependencies, keys) {
        val found = keys.mapNotNull { key -> runCatchingPreservingCancellation { dependencies.source(key) }.getOrNull() }
        value = found.associate { it.key to it.label(found) }
    }
    return remember(shell, followed, names) {
        followedSeriesEntries(followed.map(FollowedSeries::series), names, dependencies.covers) { id ->
            shell.navController.navigate(AppRoute.series(id))
        }
    }
}

/** A row per series in [series]: its kind and, when [sourceNames] has it, source under the title, and its cover. */
internal fun followedSeriesEntries(
    series: List<Series>,
    sourceNames: Map<SourceKey, String>,
    covers: SeriesCovers,
    open: (SeriesId) -> Unit,
): List<OmniboxEntry> = series.map { item ->
    val source = item.id.source
    OmniboxEntry.Action(
        OmniboxKind.SERIES,
        item.title,
        detail = listOfNotNull(source.seriesKind.label, sourceNames[source]).joinToString(" · "),
        cover = covers.address(item)?.let { listOf(CodexCoverCandidate.RemoteImage(source, it)) }.orEmpty(),
    ) { open(item.id) }
}

/** The creators of [watched] posts, latest first and each once, leaving out those already [followed]. */
internal fun recentCreators(
    watched: List<RecentPostEntry>,
    followed: List<CreatorProfile>,
    limit: Int = RECENT_CREATOR_LIMIT,
): List<CreatorProfile> {
    fun CreatorProfile.key() = source to (profileId ?: displayName)
    val seen = followed.mapTo(mutableSetOf()) { it.key() }
    return watched.sortedByDescending(RecentPostEntry::viewedAtEpochMs).asSequence()
        .flatMap { it.post.creatorProfiles }
        .filter { seen.add(it.key()) }
        .take(limit)
        .toList()
}

/** Opens a saved search. Its "new posts" count is read once Search reports the results it showed ([SavedSearchOpenMarks]). */
internal fun TheoriaShell.openSavedSearch(entry: SavedSearchEntry) {
    openHistoricalSearch(entry.search)
    savedSearchOpens.opened(entry)
}
