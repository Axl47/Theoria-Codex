package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.codex.rememberCodexPostDurations
import com.theoriacodex.app.codex.rememberCodexPostUsage
import com.theoriacodex.app.codex.rememberNewPostsReload
import com.theoriacodex.app.media.MediaDurationStates
import com.theoriacodex.app.codex.sortLiveCodexPosts
import com.theoriacodex.app.codex.SAVED_SEARCHES_CODEX_ID
import com.theoriacodex.app.codex.SavedSearchesCodexViewModel
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.di.FeatureDependencies
import com.theoriacodex.app.di.SourceDependencies
import com.theoriacodex.app.ui.components.FeedTraceSections
import com.theoriacodex.app.ui.components.rememberComputedOffMain
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.app.source.creatorBrowsingSources
import com.theoriacodex.domain.model.Codex

@Composable
internal fun SavedSearchesCodexBoundary(
    data: DataDependencies,
    sources: SourceDependencies,
    features: FeatureDependencies,
    settings: com.theoriacodex.data.repository.AppSettings,
    filters: FeedFabRestoreState,
    sort: CodexSortMode,
    durationStates: MediaDurationStates,
    content: @Composable (CodexDetailDestinationState) -> Unit,
) {
    val entries = rememberRetained("saved-searches") { data.savedSearches.observeSavedSearches() }.value ?: return
    val news = rememberRetained("saved-search-new-posts") { data.uiRestoreRepository.observeSavedSearchNewPosts() }.value ?: return
    val available = sources.availableSources.collectAsStateWithLifecycle().value
    val owner = viewModel<SavedSearchesCodexViewModel>(
        viewModelStoreOwner = com.theoriacodex.app.codex.LocalSavedCodexSessionOwner.current
            ?: checkNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current),
        factory = SavedSearchesCodexViewModel.factory(features.search, data.cacheRepository, features.relatedPosts),
    )
    val selected = remember(entries, filters.excludedSavedSearches) {
        val excluded = filters.excludedSavedSearches.toSet()
        entries.filterNot { it.id in excluded }
    }
    val usage = rememberCodexPostUsage(data.statisticsRepository, SAVED_SEARCHES_CODEX_ID, sort).orEmpty()
    val feed = owner.state.collectAsStateWithLifecycle().value
    val reloading = rememberNewPostsReload(owner, feed, selected.mapNotNull { news.marks[it.id] })
    val durations = rememberCodexPostDurations(feed.posts, sort, durationStates, SAVED_SEARCHES_CODEX_ID)
    LaunchedEffect(selected, settings.runtime, available, sort, usage, durations) {
        owner.synchronize(selected, settings, available, sort, usage, durations)
    }
    val posts = rememberComputedOffMain(
        feed.posts, sort, usage, durations, section = FeedTraceSections.SORT, retainKey = "codex-sort:$SAVED_SEARCHES_CODEX_ID",
    ) {
        sortLiveCodexPosts(feed.posts, sort, usage, durations)
    }
    content(CodexDetailDestinationState(
        codex = Codex(SAVED_SEARCHES_CODEX_ID, "Searches", 0), items = emptyList(), posts = posts,
        activeProfile = settings.activeRecommendationProfile(), availableSources = available,
        creatorBrowsingSources = remember(sources.registry, available) { sources.registry.creatorBrowsingSources().intersect(available) },
        resolveUnknownAnimatedDurations = settings.contentFilters.resolveUnknownAnimatedDurations,
        savedSearchOwner = owner, savedSearches = entries, loading = feed.loading || reloading,
    ))
}
