package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.appshell.PendingIncomingUri
import com.theoriacodex.app.codex.CodexCollectionSource
import com.theoriacodex.app.codex.CodexCollectionPresentation
import com.theoriacodex.app.codex.CodexActionOptions
import com.theoriacodex.app.codex.FOLLOWED_CODEX_ID
import kotlinx.coroutines.flow.Flow
import com.theoriacodex.app.codex.CodexCoverCandidate
import com.theoriacodex.app.codex.codexBelongsToProfile
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.di.SourceDependencies
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.settings.SettingsScreen
import com.theoriacodex.app.settings.SettingsViewModel
import com.theoriacodex.app.source.creatorBrowsingSources
import com.theoriacodex.app.sourceauth.CredentialStoreRecoveryState
import com.theoriacodex.data.repository.AppSettings
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
    val favoriteTags: Map<SourceKey, List<String>>,
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
)

internal data class CodexDetailDestinationState(
    val codex: Codex?,
    val items: List<CodexItem>,
    val posts: List<Post>,
    val activeProfile: RecommendationProfile,
    val availableSources: Set<SourceKey>,
    val creatorBrowsingSources: Set<SourceKey>,
    val resolveUnknownAnimatedDurations: Boolean,
    val followedOwner: com.theoriacodex.app.codex.FollowedCodexViewModel? = null,
    val follows: List<com.theoriacodex.data.repository.FollowedCreator> = emptyList(),
    val loading: Boolean = false,
)

internal data class SaveToCodexDestinationState(
    val settings: AppSettings,
    val activeProfile: RecommendationProfile,
    val codicesByProfile: Map<String, List<Codex>>,
    val itemCounts: Map<String, Int>,
    val coverCandidates: Map<String, List<CodexCoverCandidate>>,
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
    val settingsState = data.settingsRepository.observeSettings()
        .collectAsStateWithLifecycle(initialValue = null)
    val settings = settingsState.value ?: return
    DestinationStateBoundary(settingsState) {
        val activeProfile = settings.activeRecommendationProfile()
        val availableSourcesState = sources.availableSources.collectAsStateWithLifecycle()
        val likedPostIdsState = data.likesRepository.observeLikedPostIds(activeProfile.profileId)
            .collectAsStateWithLifecycle(initialValue = null)
        val activeProfileLikesState = data.likesRepository.observeLikes(activeProfile.profileId)
            .collectAsStateWithLifecycle(initialValue = null)
        val watchedPostsState = data.recentsRepository.observeWatchedPosts()
            .collectAsStateWithLifecycle(initialValue = null)
        val savedPostIds = rememberSavedPostIds(data)
        val likedPostIds = likedPostIdsState.value ?: return@DestinationStateBoundary
        val activeProfileLikes = activeProfileLikesState.value ?: return@DestinationStateBoundary
        val watchedPosts = watchedPostsState.value ?: return@DestinationStateBoundary
        val availableSources = availableSourcesState.value
        content(
            BrowsingDestinationState(
                settings = settings,
                activeProfile = activeProfile,
                availableSources = availableSources,
                creatorBrowsingSources = remember(sources.registry, availableSources) {
                    sources.registry.creatorBrowsingSources().intersect(availableSources)
                },
                likedPostIds = likedPostIds,
                activeProfileLikesCount = activeProfileLikes.size,
                favoriteTags = remember(settings.favoriteTagsByProfile, activeProfile.profileId) {
                    settings.favoriteTagsByProfile[activeProfile.profileId]
                        .orEmpty()
                        .groupBy { it.source }
                        .mapValues { (_, entries) -> entries.map { it.tag } }
                },
                savedPostIds = savedPostIds,
                watchedPostIds = remember(watchedPosts) {
                    watchedPosts
                        .asSequence()
                        .filter { entry -> entry.section == RecentPostSection.WATCHED }
                        .mapTo(linkedSetOf()) { entry -> entry.post.id }
                },
            ),
        )
    }
}

@Composable
internal fun RecentsDestinationStateBoundary(
    data: DataDependencies,
    content: @Composable (RecentsDestinationState) -> Unit,
) {
    val watchedState = data.recentsRepository.observeWatchedPosts()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val searchesState = data.recentsRepository.observeSearches()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val activityState = data.recentsRepository.observeActivity()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val settingsState = data.settingsRepository.observeSettings()
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    DestinationStateBoundary(settingsState) { settings ->
        val profile = settings.activeRecommendationProfile()
        val likedState = data.likesRepository.observeLikedPostIds(profile.profileId)
            .collectAsStateWithLifecycle(initialValue = emptySet())
        val watched = watchedState.value
        val searches = searchesState.value
        content(
            RecentsDestinationState(
                watchedPosts = watched.filter { it.section == RecentPostSection.WATCHED },
                codexPosts = watched.filter { it.section == RecentPostSection.CODEX },
                searches = searches.filterNot { it.kind == RecentSearchKind.FYP },
                fypSearches = searches.filter { it.kind == RecentSearchKind.FYP },
                activity = activityState.value,
                likedPostIds = likedState.value,
            ),
        )
    }
}

@Composable
internal fun CodexDestinationStateBoundary(
    data: DataDependencies,
    sources: SourceDependencies,
    thumbnailCacheGeneration: Int,
    content: @Composable (CodexDestinationState) -> Unit,
) {
    val settingsState = data.settingsRepository.observeSettings()
        .collectAsStateWithLifecycle(initialValue = null)
    val settings = settingsState.value ?: return
    val codicesState = data.codexRepository.observeCodices()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val availableSourcesState = sources.availableSources.collectAsStateWithLifecycle()
    val codices = codicesState.value
    val availableSources = availableSourcesState.value
    val source = remember(data.codexRepository, data.storageDirectory) {
        CodexCollectionSource(data.codexRepository, data.storageDirectory)
    }
    DestinationStateBoundary(settingsState) {
        val profile = settings.activeRecommendationProfile()
        val visible = remember(codices, profile.profileId) {
            codices.filter { codexBelongsToProfile(it.codexId, profile.profileId) }
        }
        val collection = rememberCodexCollectionState(data, visible, thumbnailCacheGeneration)
        val observeActions = remember(source, availableSources) {
            { id: String -> source.observeActionOptions(id, availableSources) }
        }
        content(
            CodexDestinationState(
                allCodices = codices,
                visibleCodices = visible,
                activeProfile = profile,
                itemCounts = collection.itemCounts + (FOLLOWED_CODEX_ID to settings.followedCreators.size),
                coverCandidates = collection.coverCandidates,
                observeActionOptions = observeActions,
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
    if (codexId == com.theoriacodex.app.codex.FOLLOWED_CODEX_ID) {
        FollowedCodexDestinationStateBoundary(data, sources, fabRestoreState, sortMode, content)
        return
    }
    val codexState = data.codexRepository.observeCodex(codexId)
        .collectAsStateWithLifecycle(initialValue = null)
    val itemsState = data.codexRepository.observeCodexItems(codexId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val postsState = data.codexRepository.observeCodexPosts(codexId, sortMode)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val settingsState = data.settingsRepository.observeSettings()
        .collectAsStateWithLifecycle(initialValue = null)
    val settings = settingsState.value ?: return
    val availableState = sources.availableSources.collectAsStateWithLifecycle()
    DestinationStateBoundary(settingsState) {
        content(
            CodexDetailDestinationState(
                codex = codexState.value,
                items = itemsState.value,
                posts = postsState.value,
                activeProfile = settings.activeRecommendationProfile(),
                availableSources = availableState.value,
                creatorBrowsingSources = remember(sources.registry, availableState.value) {
                    sources.registry.creatorBrowsingSources().intersect(availableState.value)
                },
                resolveUnknownAnimatedDurations =
                    settings.contentFilters.resolveUnknownAnimatedDurations,
            ),
        )
    }
}

@Composable
internal fun SaveToCodexDestinationStateBoundary(
    data: DataDependencies,
    content: @Composable (SaveToCodexDestinationState) -> Unit,
) {
    val settingsState = data.settingsRepository.observeSettings()
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    val codicesState = data.codexRepository.observeCodices()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val codices = codicesState.value
    val collection = rememberCodexCollectionState(data = data, codices = codices)
    DestinationStateBoundary(settingsState) { settings ->
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
            ),
        )
    }
}

@Composable
internal fun SettingsDestinationStateBoundary(owner: SettingsViewModel) {
    val state = owner.state.collectAsStateWithLifecycle()
    DestinationStateBoundary(state) { SettingsScreen(state = it, onAction = owner::onAction) }
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
    val settingsState = settingsRepository.observeSettings()
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    val profile = settingsState.value.activeRecommendationProfile()
    LaunchedEffect(profile.profileId, profile.name) { onProfileChanged(profile) }
}

internal fun AppSettings.activeRecommendationProfile(): RecommendationProfile {
    return recommendationProfiles.firstOrNull { it.profileId == activeProfileId }
        ?: recommendationProfiles.firstOrNull()
        ?: RecommendationProfile(profileId = "profile-main", name = "Main")
}

@Composable
private fun rememberCodexCollectionState(
    data: DataDependencies,
    codices: List<Codex>,
    refreshKey: Any? = Unit,
): CodexCollectionPresentation {
    val ids = remember(codices) { codices.mapTo(linkedSetOf(), Codex::codexId) }
    val summaries = remember(data.codexRepository, ids, refreshKey) {
        CodexCollectionSource(data.codexRepository, data.storageDirectory).observe(ids)
    }
    return key(ids, refreshKey) {
        summaries.collectAsStateWithLifecycle(initialValue = CodexCollectionPresentation()).value
    }
}

@Composable
private fun rememberSavedPostIds(data: DataDependencies): Set<PostId> {
    val codices by data.codexRepository.observeCodices().collectAsStateWithLifecycle(initialValue = emptyList())
    val ids = remember(codices) { codices.mapTo(linkedSetOf(), Codex::codexId) }
    val saved = remember(data.codexRepository, ids) { data.codexRepository.observeSavedPostIds(ids) }
    return saved.collectAsStateWithLifecycle(initialValue = emptySet()).value
}

@Composable
private fun FollowedCodexDestinationStateBoundary(
    data: DataDependencies,
    sources: SourceDependencies,
    filters: com.theoriacodex.data.repository.FeedFabRestoreState,
    sort: CodexSortMode,
    content: @Composable (CodexDetailDestinationState) -> Unit,
) {
    val settings = data.settingsRepository.observeSettings().collectAsStateWithLifecycle(initialValue = null).value
        ?: return
    val available = sources.availableSources.collectAsStateWithLifecycle().value
    val owner = androidx.lifecycle.viewmodel.compose.viewModel<com.theoriacodex.app.codex.FollowedCodexViewModel>(
        factory = com.theoriacodex.app.codex.FollowedCodexViewModel.factory(sources.registry, data.cacheRepository),
    )
    val selected = remember(settings.followedCreators, filters.followedSources, filters.followedAuthors) {
        com.theoriacodex.app.codex.selectFollowedCreators(
            settings.followedCreators, filters.followedSources.toSet(), filters.followedAuthors.toSet(),
        )
    }
    androidx.compose.runtime.LaunchedEffect(selected, available) { owner.synchronize(selected, available) }
    val feed = owner.state.collectAsStateWithLifecycle().value
    val posts = remember(feed.posts, sort) {
        when (sort) {
            CodexSortMode.NEWEST_SAVED -> feed.posts.sortedByDescending { it.createdAtEpochMs }
            CodexSortMode.OLDEST_SAVED -> feed.posts.sortedBy { it.createdAtEpochMs }
            CodexSortMode.BY_SOURCE -> feed.posts.sortedBy { it.id.source.ordinal }
        }
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
