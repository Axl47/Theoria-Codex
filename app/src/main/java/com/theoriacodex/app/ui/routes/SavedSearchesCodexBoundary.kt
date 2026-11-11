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
    settings: com.theoriacodex.data.repository.AppSettings,
    filters: FeedFabRestoreState,
    sort: CodexSortMode,
    content: @Composable (CodexDetailDestinationState) -> Unit,
) {
    val entries = rememberRetained("saved-searches") { data.savedSearches.observeSavedSearches() }.value ?: return
    val available = sources.availableSources.collectAsStateWithLifecycle().value
    val execution = remember(data, sources.registry) {
        SearchCoordinator(sources.registry, data.queryRepository, data.settingsRepository, data.uiRestoreRepository,
            data.recentsRepository, data.statisticsRepository, postResolutions = sources.postResolutions)
    }
    val owner = viewModel<SavedSearchesCodexViewModel>(
        viewModelStoreOwner = com.theoriacodex.app.codex.LocalSavedCodexSessionOwner.current
            ?: checkNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current),
        factory = SavedSearchesCodexViewModel.factory(
            execution, data.cacheRepository, com.theoriacodex.app.related.RelatedPostsLoader(sources.registry),
        ),
    )
    val selected = remember(entries, filters.excludedSavedSearches) {
        val excluded = filters.excludedSavedSearches.toSet()
        entries.filterNot { it.id in excluded }
    }
    LaunchedEffect(selected, settings.runtime, available, sort) { owner.synchronize(selected, settings, available, sort) }
    val feed = owner.state.collectAsStateWithLifecycle().value
    val posts = remember(feed.posts, sort) { sortLiveCodexPosts(feed.posts, sort) }
    content(CodexDetailDestinationState(
        codex = Codex(SAVED_SEARCHES_CODEX_ID, "Searches", 0), items = emptyList(), posts = posts,
        activeProfile = settings.activeRecommendationProfile(), availableSources = available,
        creatorBrowsingSources = remember(sources.registry, available) { sources.registry.creatorBrowsingSources().intersect(available) },
        resolveUnknownAnimatedDurations = settings.contentFilters.resolveUnknownAnimatedDurations,
        savedSearchOwner = owner, savedSearches = entries, loading = feed.loading,
    ))
}
