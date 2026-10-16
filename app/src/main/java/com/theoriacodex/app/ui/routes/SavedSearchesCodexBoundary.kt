package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.codex.sortLiveCodexPosts
import com.theoriacodex.app.codex.SAVED_SEARCHES_CODEX_ID
import com.theoriacodex.app.codex.SavedSearchesCodexViewModel
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.di.SourceDependencies
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.app.source.creatorBrowsingSources
import com.theoriacodex.domain.model.Codex

@Composable
internal fun SavedSearchesCodexBoundary(
    data: DataDependencies,
    sources: SourceDependencies,
    filters: FeedFabRestoreState,
    sort: CodexSortMode,
    content: @Composable (CodexDetailDestinationState) -> Unit,
) {
    val settings = remember(data.settingsRepository) { data.settingsRepository.observeSettings() }
        .collectAsStateWithLifecycle(initialValue = null).value ?: return
    val entries = remember(data.savedSearches) { data.savedSearches.observeSavedSearches() }
        .collectAsStateWithLifecycle(initialValue = null).value ?: return
    val available = sources.availableSources.collectAsStateWithLifecycle().value
    val execution = remember(data, sources.registry) {
        SearchCoordinator(sources.registry, data.queryRepository, data.settingsRepository, data.uiRestoreRepository,
            data.recentsRepository, data.statisticsRepository)
    }
    val owner = viewModel<SavedSearchesCodexViewModel>(factory = SavedSearchesCodexViewModel.factory(execution, data.cacheRepository))
    val selected = remember(entries, filters.excludedSavedSearches) {
        val excluded = filters.excludedSavedSearches.toSet()
        entries.filterNot { it.id in excluded }
    }
    LaunchedEffect(selected, settings.runtime, available, sort) { owner.synchronize(selected, settings, available, sort) }
    val feed = owner.state.collectAsStateWithLifecycle().value
    val posts = remember(feed.posts, sort) { sortLiveCodexPosts(feed.posts, sort) }
    content(CodexDetailDestinationState(
        codex = Codex(SAVED_SEARCHES_CODEX_ID, "Saved Searches", 0), items = emptyList(), posts = posts,
        activeProfile = settings.activeRecommendationProfile(), availableSources = available,
        creatorBrowsingSources = remember(sources.registry, available) { sources.registry.creatorBrowsingSources().intersect(available) },
        resolveUnknownAnimatedDurations = settings.contentFilters.resolveUnknownAnimatedDurations,
        savedSearchOwner = owner, savedSearches = entries, loading = feed.loading,
    ))
}
