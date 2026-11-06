package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.appshell.PendingIncomingUri
import com.theoriacodex.app.codex.LocalCodexSaveRelevanceLoader
import com.theoriacodex.app.codex.sortLiveCodexPosts
import com.theoriacodex.app.codex.CodexCollectionSource
import com.theoriacodex.app.codex.CodexCollectionPresentation
import com.theoriacodex.app.codex.CodexActionOptions
import com.theoriacodex.app.codex.CodexLibraryUsage
import com.theoriacodex.app.codex.FOLLOWED_CODEX_ID
import com.theoriacodex.app.codex.observeCodexLibraryUsage
import com.theoriacodex.app.codex.usesUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import com.theoriacodex.app.codex.CodexCoverCandidate
import com.theoriacodex.app.codex.codexBelongsToProfile
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.di.SourceDependencies
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.settings.SettingsScreen
import com.theoriacodex.app.settings.SettingsViewModel
import com.theoriacodex.app.source.creatorBrowsingSources
import com.theoriacodex.app.ui.findActivity
import com.theoriacodex.app.sourceauth.CredentialStoreRecoveryState
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.FavoriteTagEntry
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class BrowsingDestinationState(
    val settings: AppSettings,
    val activeProfile: RecommendationProfile,
    val availableSources: Set<SourceKey>,
    val creatorBrowsingSources: Set<SourceKey>,
    val likedPostIds: Set<PostId>,
    val activeProfileLikesCount: Int,
    val favoriteTags: List<FavoriteTagEntry>,
    val savedPostIds: Set<PostId>,
    val watchedPostIds: Set<PostId>,
) {
    val unknownAnimatedDurationPolicy: UnknownAnimatedDurationPolicy
        get() = if (settings.contentFilters.resolveUnknownAnimatedDurations) {
            UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND
        } else {
            UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS
        }
}

internal data class RecentsDestinationState(
    val watchedPosts: List<RecentPostEntry>,
    val codexPosts: List<RecentPostEntry>,
    val searches: List<RecentSearchEntry>,
    val fypSearches: List<RecentSearchEntry>,
    val activity: List<RecentActivityEntry>,
    val likedPostIds: Set<PostId>,
)

internal data class CodexDestinationState(
    val allCodices: List<Codex>,
    val visibleCodices: List<Codex>,
    val activeProfile: RecommendationProfile,
    val itemCounts: Map<String, Int>,
    val coverCandidates: Map<String, List<CodexCoverCandidate>>,
    val observeActionOptions: (String) -> Flow<CodexActionOptions>,
    val librarySort: com.theoriacodex.data.repository.CodexLibrarySort,
    val libraryUsage: Map<String, CodexLibraryUsage>?,
)

internal data class CodexDetailDestinationState(
    val codex: Codex?,
    val items: List<CodexItem>,
    val posts: List<Post>,
    val activeProfile: RecommendationProfile,
    val availableSources: Set<SourceKey>,
    val creatorBrowsingSources: Set<SourceKey>,
    val resolveUnknownAnimatedDurations: Boolean,
    val savedSearchOwner: com.theoriacodex.app.codex.SavedSearchesCodexViewModel? = null,
    val savedSearches: List<com.theoriacodex.data.repository.SavedSearchEntry> = emptyList(),
    val followedOwner: com.theoriacodex.app.codex.FollowedCodexViewModel? = null,
    val follows: List<com.theoriacodex.data.repository.FollowedCreator> = emptyList(),
    val loading: Boolean = false,
    val likedPostIds: Set<PostId> = emptySet(),
)

internal data class SaveToCodexDestinationState(
    val settings: AppSettings,
    val activeProfile: RecommendationProfile,
    val codicesByProfile: Map<String, List<Codex>>,
    val itemCounts: Map<String, Int>,
    val coverCandidates: Map<String, List<CodexCoverCandidate>>,
    val relevanceScores: Map<String, Double>,
    val containingCodexIds: Set<String>,
    val libraryUsage: Map<String, CodexLibraryUsage>,
)

internal data class ViewerDestinationState(
    val browsing: BrowsingDestinationState,
    val search: com.theoriacodex.app.search.state.SearchUiState,
    val forYou: com.theoriacodex.app.recommend.state.ForYouUiState,
    val creator: com.theoriacodex.app.creator.state.CreatorUiState,
)

/** The state read belongs to this restart scope, never to the caller's shell scope. */
@Composable
internal fun <T> DestinationStateBoundary(
    state: State<T>,
    content: @Composable (T) -> Unit,
) {
    val currentContent = rememberUpdatedState(content)
    currentContent.value(state.value)
}

@Composable
internal fun BrowsingDestinationStateBoundary(
    data: DataDependencies,
    sources: SourceDependencies,
    content: @Composable (BrowsingDestinationState) -> Unit,
) {
    val metadata = browsingMetadataOwner(data).state.collectAsStateWithLifecycle().value ?: return
    val settings = metadata.settings
    val activeProfile = settings.activeRecommendationProfile()
    val available by sources.availableSources.collectAsStateWithLifecycle()
    content(BrowsingDestinationState(
        settings = settings,
        activeProfile = activeProfile,
        availableSources = available,
        creatorBrowsingSources = remember(sources.registry, available) {
            sources.registry.creatorBrowsingSources().intersect(available)
        },
        likedPostIds = metadata.likedPostIds,
        activeProfileLikesCount = metadata.likedPostIds.size,
        favoriteTags = remember(settings.favoriteTagsByProfile, activeProfile.profileId) {
            settings.favoriteTagsByProfile[activeProfile.profileId].orEmpty()
        },
        savedPostIds = metadata.savedPostIds,
        watchedPostIds = metadata.watchedPostIds,
    ))
}

@Composable
internal fun RecentsDestinationStateBoundary(
    data: DataDependencies,
    content: @Composable (RecentsDestinationState) -> Unit,
) {
    val recentState = browsingMetadataOwner(data).recents.collectAsStateWithLifecycle().value ?: return
    content(recentState)
}

/**
 * Shared across the whole activity, not per navigation entry: a Viewer or Codex opened from a feed
 * must not wait on its own fresh queries for state the app already has.
 */
@Composable
internal fun browsingMetadataOwner(data: DataDependencies): BrowsingMetadataViewModel = viewModel(
    viewModelStoreOwner = LocalContext.current.findActivity() as? ViewModelStoreOwner
        ?: checkNotNull(LocalViewModelStoreOwner.current),
    key = "browsing-metadata",
    factory = remember(data) { viewModelFactory {
        initializer { BrowsingMetadataViewModel(data.settingsRepository, data.likesRepository,
            data.codexRepository, data.recentsRepository) }
    } },
)

@Composable
internal fun CodexDestinationStateBoundary(
    data: DataDependencies,
    sources: SourceDependencies,
    thumbnailCacheGeneration: Int,
    savedSortFor: (String) -> CodexSortMode = { CodexSortMode.NEWEST_SAVED },
    content: @Composable (CodexDestinationState) -> Unit,
) {
    val settingsState = rememberRetained("settings") { data.settingsRepository.observeSettings() }
    val settings = settingsState.value ?: return
    val codicesState = rememberRetained("codices") { data.codexRepository.observeCodices() }
    val availableSourcesState = sources.availableSources.collectAsStateWithLifecycle()
    // A placeholder grid with only virtual tiles would clamp or move the restored collection anchor.
    val codices = codicesState.value ?: return
    val savedSearches = rememberRetained("saved-searches") { data.savedSearches.observeSavedSearches() }.value ?: return
    val availableSources = availableSourcesState.value
    val source = remember(data.codexRepository, data.storageDirectory, data.cacheRepository) {
        CodexCollectionSource(data.codexRepository, data.storageDirectory, data.cacheRepository)
    }
    DestinationStateBoundary(settingsState) {
        val profile = settings.activeRecommendationProfile()
        val visible = remember(codices, profile.profileId) {
            codices.filter { codexBelongsToProfile(it.codexId, profile.profileId) }
        }
        val collectionIds = remember(visible) { visible.mapTo(linkedSetOf(), Codex::codexId) }
        val collection = rememberRetained("collection:$collectionIds:$thumbnailCacheGeneration") {
            source.observe(collectionIds)
        }.value ?: CodexCollectionPresentation()
        val observeActions = remember(source, availableSources) {
            { id: String -> source.observeActionOptions(id, availableSources) }
        }
        CodexContentWarmUp(data, collection.itemCounts, savedSortFor)
        // Subscribed only while a usage sort needs it, since statistics are written during use.
        val libraryUsage = if (settings.codexLibrarySort.usesUsage) {
            rememberRetained("library-usage") { data.statisticsRepository.observeCodexLibraryUsage() }.value
        } else {
            null
        }
        content(
            CodexDestinationState(
                allCodices = codices,
                visibleCodices = visible,
                activeProfile = profile,
                itemCounts = collection.itemCounts + (FOLLOWED_CODEX_ID to settings.followedCreators.size) +
                    (com.theoriacodex.app.codex.SAVED_SEARCHES_CODEX_ID to savedSearches.size),
                coverCandidates = collection.coverCandidates,
                observeActionOptions = observeActions,
                librarySort = settings.codexLibrarySort,
                libraryUsage = libraryUsage,
            ),
        )
    }
}

@Composable
internal fun CodexDetailDestinationStateBoundary(
    codexId: String,
    sortMode: CodexSortMode,
    data: DataDependencies,
    sources: SourceDependencies,
    fabRestoreState: com.theoriacodex.data.repository.FeedFabRestoreState,
    content: @Composable (CodexDetailDestinationState) -> Unit,
) {
    val metadata = browsingMetadataOwner(data).state.collectAsStateWithLifecycle().value ?: return
    val render: @Composable (CodexDetailDestinationState) -> Unit = { state ->
        content(state.copy(likedPostIds = if (state.activeProfile.profileId == metadata.settings.activeProfileId)
            metadata.likedPostIds else emptySet()))
    }
    if (codexId == com.theoriacodex.app.codex.SAVED_SEARCHES_CODEX_ID) {
        SavedSearchesCodexBoundary(data, sources, metadata.settings, fabRestoreState, sortMode, render)
        return
    }
    if (codexId == com.theoriacodex.app.codex.FOLLOWED_CODEX_ID) {
        FollowedCodexDestinationStateBoundary(data, sources, metadata.settings, fabRestoreState, sortMode, render)
        return
    }
    val content = rememberCodexContentFlows()
    val lookup by rememberRetained(codexLookupKey(codexId), content) { codexLookupFlow(data, codexId) }
    val items by rememberRetained(codexItemsKey(codexId), content) { data.codexRepository.observeCodexItems(codexId) }
    val sortedPosts by rememberRetained(codexPostsKey(codexId, sortMode), content) { codexPostsFlow(data, codexId, sortMode) }
    // Changing the sort keeps showing the previous order until the new one answers.
    val shownPosts = remember(codexId) { ShownPosts() }
    sortedPosts?.let { shownPosts.latest = it }
    val posts = shownPosts.latest
    val available by sources.availableSources.collectAsStateWithLifecycle()
    val settings = metadata.settings
    render(
        CodexDetailDestinationState(
            codex = lookup?.codex.takeIf { posts != null },
            items = items.orEmpty(),
            posts = posts.orEmpty(),
            activeProfile = settings.activeRecommendationProfile(),
            availableSources = available,
            creatorBrowsingSources = remember(sources.registry, available) {
                sources.registry.creatorBrowsingSources().intersect(available)
            },
            resolveUnknownAnimatedDurations = settings.contentFilters.resolveUnknownAnimatedDurations,
            loading = lookup == null || posts == null,
        ),
    )
}

private fun codexLookupKey(codexId: String) = "codex:$codexId"
private fun codexItemsKey(codexId: String) = "items:$codexId"
private fun codexPostsKey(codexId: String, sort: CodexSortMode) = "posts:$codexId:$sort"
private fun codexLookupFlow(data: DataDependencies, codexId: String) = data.codexRepository.observeCodex(codexId).map(::CodexLookup)
private fun codexPostsFlow(data: DataDependencies, codexId: String, sort: CodexSortMode) =
    data.codexRepository.observeCodexPosts(codexId, sort)
        .onEach { posts -> posts.forEach { data.statisticsRepository.recordPostAvailable(it) } }

private const val WARM_CODEX_MIN_ITEMS = 200
private const val WARM_CODEX_LIMIT = 2

/** Prepares the largest Codices while the Library shows, since only they outlast the opening animation. */
@Composable
private fun CodexContentWarmUp(data: DataDependencies, itemCounts: Map<String, Int>, savedSortFor: (String) -> CodexSortMode) {
    val content = rememberCodexContentFlows()
    val largest = remember(itemCounts) {
        itemCounts.entries.filter { it.value >= WARM_CODEX_MIN_ITEMS }.sortedByDescending { it.value }
            .take(WARM_CODEX_LIMIT).map { it.key }
    }
    val currentSavedSortFor by rememberUpdatedState(savedSortFor)
    largest.forEach { codexId ->
        key(codexId) {
            LaunchedEffect(content) {
                val sort = currentSavedSortFor(codexId)
                merge(
                    content.retain(codexLookupKey(codexId)) { codexLookupFlow(data, codexId) },
                    content.retain(codexItemsKey(codexId)) { data.codexRepository.observeCodexItems(codexId) },
                    content.retain(codexPostsKey(codexId, sort)) { codexPostsFlow(data, codexId, sort) },
                ).collect()
            }
        }
    }
}

/** Tells "not answered yet" from "answered that no such Codex exists", which a bare null cannot. */
private data class CodexLookup(val codex: Codex?)

private class ShownPosts {
    var latest: List<Post>? = null
}

@Composable
internal fun SaveToCodexDestinationStateBoundary(
    data: DataDependencies,
    posts: List<Post>,
    searchQuery: com.theoriacodex.domain.model.Query? = null,
    content: @Composable (SaveToCodexDestinationState) -> Unit,
) {
    // Keeps what the last open loaded, instead of restarting from defaults and empty lists each time.
    val settings = browsingMetadataOwner(data).state.collectAsStateWithLifecycle().value?.settings ?: return
    val codices = rememberRetained("save-codices") { data.codexRepository.observeCodices() }.value ?: return
    val collectionIds = remember(codices) { codices.mapTo(linkedSetOf(), Codex::codexId) }
    val collection = rememberRetained("save-collection:$collectionIds") {
        CodexCollectionSource(data.codexRepository, data.storageDirectory, data.cacheRepository).observe(collectionIds)
    }.value ?: CodexCollectionPresentation()
    val containingCodexIds = remember(data.codexRepository, codices, posts) {
        CodexCollectionSource(data.codexRepository, data.storageDirectory, data.cacheRepository)
            .observeContainingCodexIds(codices.mapTo(linkedSetOf(), Codex::codexId), posts)
    }.collectAsStateWithLifecycle(initialValue = emptySet())
    val relevanceLoader = LocalCodexSaveRelevanceLoader.current
    val relevance = if (relevanceLoader != null) {
        remember(relevanceLoader, posts, searchQuery) {
            relevanceLoader.observe(posts, searchQuery)
        }.collectAsStateWithLifecycle()
    } else {
        remember(data.codexRepository, codices, posts, searchQuery) {
            CodexCollectionSource(data.codexRepository, data.storageDirectory, data.cacheRepository)
                .observeSaveRelevance(codices.mapTo(linkedSetOf(), Codex::codexId), posts, searchQuery)
        }.collectAsStateWithLifecycle(initialValue = emptyMap())
    }
    // One reading per open: watching behind this sheet writes usage, which must not move rows under a tap.
    val libraryUsage = produceState(emptyMap<String, CodexLibraryUsage>(), data.statisticsRepository) {
        value = data.statisticsRepository.observeCodexLibraryUsage().first()
    }
    val activeProfile = settings.activeRecommendationProfile()
    content(
        SaveToCodexDestinationState(
            settings = settings,
            activeProfile = activeProfile,
            codicesByProfile = remember(codices, settings.recommendationProfiles) {
                settings.recommendationProfiles.associate { profile ->
                    profile.profileId to codices.filter {
                        codexBelongsToProfile(it.codexId, profile.profileId)
                    }
                }
            },
            itemCounts = collection.itemCounts,
            coverCandidates = collection.coverCandidates,
            relevanceScores = relevance.value,
            containingCodexIds = containingCodexIds.value,
            libraryUsage = libraryUsage.value,
        ),
    )
}

@Composable
internal fun SettingsDestinationStateBoundary(
    owner: SettingsViewModel,
    searchOwner: SearchRouteOwnerHandle?,
    storageSummary: String? = null,
    storageTools: (@Composable () -> Unit)? = null,
) {
    val state = owner.state.collectAsStateWithLifecycle()
    val emptySearch = remember { MutableStateFlow(com.theoriacodex.app.search.state.SearchUiState()) }
    val searchState = (searchOwner?.state ?: emptySearch).collectAsStateWithLifecycle()
    val query = searchState.value.query
    val selectedSources = if (searchOwner == null) emptySet() else when (val scope = query.draftSourceScope) {
        com.theoriacodex.app.search.state.SearchSourceScope.GlobalUnified -> query.unifiedSources
        else -> scope.explicitSources.toSet()
    }
    // Subscribing here keeps statistics collection scoped to a visible Settings destination.
    val statistics = owner.statistics.collectAsStateWithLifecycle()
    DestinationStateBoundary(state) {
        SettingsScreen(
            state = it, onAction = owner::onAction, statistics = statistics.value,
            searchSources = selectedSources.intersect(it.availableSources.toSet()),
            storageSummary = storageSummary, storageTools = storageTools,
        )
    }
}

@Composable
internal fun CredentialRecoveryOverlay(
    owner: SettingsViewModel,
    recoveryState: StateFlow<CredentialStoreRecoveryState>,
    content: @Composable (show: Boolean) -> Unit,
) {
    val settingsState = owner.state.collectAsStateWithLifecycle()
    val recovery = recoveryState.collectAsStateWithLifecycle()
    content(
        settingsState.value.accounts.showRecoveryDialog &&
            recovery.value == CredentialStoreRecoveryState.ReconnectRequired,
    )
}

@Composable
internal fun PixivAuthorizationCallbackEffect(
    pending: PendingIncomingUri?,
    sources: SourceDependencies,
    onConsumed: (PendingIncomingUri) -> Unit,
    onCompleted: (String?) -> Unit,
) {
    val recovery = sources.accounts.recoveryState.collectAsStateWithLifecycle()
    LaunchedEffect(recovery.value, pending) {
        val callback = pending ?: return@LaunchedEffect
        if (recovery.value != CredentialStoreRecoveryState.Ready) return@LaunchedEffect
        val result = sources.pixivAuthController.handleAuthorizationCallback(callback.value.toUri())
        onConsumed(callback)
        onCompleted(
            if (result.isSuccess) null else result.exceptionOrNull()?.message ?: "Unknown error",
        )
    }
}

@Composable
internal fun ViewerDestinationStateBoundary(
    data: DataDependencies,
    sources: SourceDependencies,
    searchOwner: SearchRouteOwnerHandle?,
    forYouOwner: ForYouRouteOwnerHandle?,
    creatorOwner: CreatorRouteOwnerHandle?,
    content: @Composable (ViewerDestinationState) -> Unit,
) {
    BrowsingDestinationStateBoundary(data, sources) { browsing ->
        val emptySearch = remember { MutableStateFlow(com.theoriacodex.app.search.state.SearchUiState()) }
        val emptyForYou = remember { MutableStateFlow(com.theoriacodex.app.recommend.state.ForYouUiState()) }
        val emptyCreator = remember { MutableStateFlow(com.theoriacodex.app.creator.state.CreatorUiState()) }
        val searchState = (searchOwner?.state ?: emptySearch).collectAsStateWithLifecycle()
        val forYouState = (forYouOwner?.state ?: emptyForYou).collectAsStateWithLifecycle()
        val creatorState = (creatorOwner?.state ?: emptyCreator).collectAsStateWithLifecycle()
        content(
            ViewerDestinationState(
                browsing = browsing,
                search = searchState.value,
                forYou = forYouState.value,
                creator = creatorState.value,
            ),
        )
    }
}

@Composable
internal fun ActiveProfileCoordinationEffect(
    settingsRepository: SettingsRepository,
    onProfileChanged: suspend (RecommendationProfile) -> Unit,
) {
    val settingsFlow = remember(settingsRepository) { settingsRepository.observeSettings() }
    // Waits for real settings, so it never acts on the default profile while they load.
    val settings = settingsFlow.collectAsStateWithLifecycle(initialValue = null).value ?: return
    val profile = settings.activeRecommendationProfile()
    LaunchedEffect(profile.profileId, profile.name) { onProfileChanged(profile) }
}

internal fun AppSettings.activeRecommendationProfile(): RecommendationProfile {
    return recommendationProfiles.firstOrNull { it.profileId == activeProfileId }
        ?: recommendationProfiles.firstOrNull()
        ?: RecommendationProfile(profileId = "profile-main", name = "Main")
}

@Composable
private fun FollowedCodexDestinationStateBoundary(
    data: DataDependencies,
    sources: SourceDependencies,
    settings: AppSettings,
    filters: com.theoriacodex.data.repository.FeedFabRestoreState,
    sort: CodexSortMode,
    content: @Composable (CodexDetailDestinationState) -> Unit,
) {
    val available = sources.availableSources.collectAsStateWithLifecycle().value
    val owner = androidx.lifecycle.viewmodel.compose.viewModel<com.theoriacodex.app.codex.FollowedCodexViewModel>(
        viewModelStoreOwner = com.theoriacodex.app.codex.LocalSavedCodexSessionOwner.current
            ?: checkNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current),
        factory = com.theoriacodex.app.codex.FollowedCodexViewModel.factory(sources.registry, data.cacheRepository),
    )
    val selected = remember(settings.followedCreators, filters.followedSources, filters.followedAuthors) {
        com.theoriacodex.app.codex.selectFollowedCreators(
            settings.followedCreators, filters.followedSources.toSet(), filters.followedAuthors.toSet(),
        )
    }
    androidx.compose.runtime.LaunchedEffect(selected, available, sort) { owner.synchronize(selected, available, sort) }
    val feed = owner.state.collectAsStateWithLifecycle().value
    val posts = remember(feed.posts, sort) {
        sortLiveCodexPosts(feed.posts, sort)
    }
    content(CodexDetailDestinationState(
        codex = Codex(com.theoriacodex.app.codex.FOLLOWED_CODEX_ID, "Followed", 0),
        items = emptyList(), posts = posts, activeProfile = settings.activeRecommendationProfile(),
        availableSources = available,
        creatorBrowsingSources = remember(sources.registry, available) {
            sources.registry.creatorBrowsingSources().intersect(available)
        },
        resolveUnknownAnimatedDurations = settings.contentFilters.resolveUnknownAnimatedDurations,
        followedOwner = owner, follows = settings.followedCreators, loading = feed.loading,
    ))
}
