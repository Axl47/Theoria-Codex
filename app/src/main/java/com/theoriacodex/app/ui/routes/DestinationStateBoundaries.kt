package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.theoriacodex.app.codex.CodexSearchSourceOption
import com.theoriacodex.app.codex.CodexSearchTagOption
import com.theoriacodex.app.codex.codexBelongsToProfile
import com.theoriacodex.app.codex.codexSearchSourceOptions
import com.theoriacodex.app.codex.codexSearchTagOptions
import com.theoriacodex.app.appshell.PendingIncomingUri
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.di.SourceDependencies
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.settings.SettingsScreen
import com.theoriacodex.app.settings.SettingsViewModel
import com.theoriacodex.app.source.creatorBrowsingSources
import com.theoriacodex.app.sourceauth.CredentialStoreRecoveryState
import com.theoriacodex.app.ui.resolveCodexCoverModel
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class BrowsingDestinationState(
    val settings: AppSettings,
    val activeProfile: RecommendationProfile,
    val availableSources: Set<SourceKey>,
    val creatorBrowsingSources: Set<SourceKey>,
    val likedPostIds: Set<PostId>,
    val activeProfileLikesCount: Int,
    val favoriteTags: Map<SourceKey, List<String>>,
    val savedPostIds: Set<PostId>,
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
    val activity: List<RecentActivityEntry>,
    val likedPostIds: Set<PostId>,
)

internal data class CodexDestinationState(
    val allCodices: List<Codex>,
    val visibleCodices: List<Codex>,
    val activeProfile: RecommendationProfile,
    val itemCounts: Map<String, Int>,
    val coverModels: Map<String, Any?>,
    val searchSourceOptions: Map<String, List<CodexSearchSourceOption>>,
    val searchTagOptions: Map<String, Map<SourceKey, List<CodexSearchTagOption>>>,
)

internal data class CodexDetailDestinationState(
    val codex: Codex?,
    val items: List<CodexItem>,
    val posts: List<Post>,
    val creatorBrowsingSources: Set<SourceKey>,
)

internal data class SaveToCodexDestinationState(
    val settings: AppSettings,
    val activeProfile: RecommendationProfile,
    val codicesByProfile: Map<String, List<Codex>>,
    val itemCounts: Map<String, Int>,
    val coverModels: Map<String, Any?>,
)

private data class CodexCollectionState(
    val itemCounts: Map<String, Int>,
    val coverModels: Map<String, Any?>,
    val searchSourceOptions: Map<String, List<CodexSearchSourceOption>>,
    val searchTagOptions: Map<String, Map<SourceKey, List<CodexSearchTagOption>>>,
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
        val savedPostIds = rememberSavedPostIds(data)
        val likedPostIds = likedPostIdsState.value ?: return@DestinationStateBoundary
        val activeProfileLikes = activeProfileLikesState.value ?: return@DestinationStateBoundary
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
        content(
            RecentsDestinationState(
                watchedPosts = watched.filter { it.section == RecentPostSection.WATCHED },
                codexPosts = watched.filter { it.section == RecentPostSection.CODEX },
                searches = searchesState.value,
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
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    val codicesState = data.codexRepository.observeCodices()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val availableSourcesState = sources.availableSources.collectAsStateWithLifecycle()
    val codices = codicesState.value
    val availableSources = availableSourcesState.value
    val collection = rememberCodexCollectionState(
        data = data,
        codices = codices,
        availableSources = availableSources,
        refreshKey = thumbnailCacheGeneration,
    )

    DestinationStateBoundary(settingsState) { settings ->
        val profile = settings.activeRecommendationProfile()
        content(
            CodexDestinationState(
                allCodices = codices,
                visibleCodices = remember(codices, profile.profileId) {
                    codices.filter { codexBelongsToProfile(it.codexId, profile.profileId) }
                },
                activeProfile = profile,
                itemCounts = collection.itemCounts,
                coverModels = collection.coverModels,
                searchSourceOptions = collection.searchSourceOptions,
                searchTagOptions = collection.searchTagOptions,
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
    content: @Composable (CodexDetailDestinationState) -> Unit,
) {
    val codexState = data.codexRepository.observeCodex(codexId)
        .collectAsStateWithLifecycle(initialValue = null)
    val itemsState = data.codexRepository.observeCodexItems(codexId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val postsState = data.codexRepository.observeCodexPosts(codexId, sortMode)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val availableState = sources.availableSources.collectAsStateWithLifecycle()
    content(
        CodexDetailDestinationState(
            codex = codexState.value,
            items = itemsState.value,
            posts = postsState.value,
            creatorBrowsingSources = remember(sources.registry, availableState.value) {
                sources.registry.creatorBrowsingSources().intersect(availableState.value)
            },
        ),
    )
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
                coverModels = collection.coverModels,
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
    availableSources: Set<SourceKey>? = null,
    refreshKey: Any? = Unit,
): CodexCollectionState {
    val itemCounts = remember { mutableStateMapOf<String, Int>() }
    val coverModels = remember { mutableStateMapOf<String, Any?>() }
    val sourceOptions = remember { mutableStateMapOf<String, List<CodexSearchSourceOption>>() }
    val tagOptions = remember { mutableStateMapOf<String, Map<SourceKey, List<CodexSearchTagOption>>>() }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, codices.map(Codex::codexId), availableSources, refreshKey) {
        val activeIds = codices.map(Codex::codexId).toSet()
        listOf(itemCounts, coverModels, sourceOptions, tagOptions).forEach { map ->
            map.keys.filterNot(activeIds::contains).toList().forEach(map::remove)
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            coroutineScope {
                codices.forEach { codex ->
                    launch {
                        data.codexRepository.observeCodexItems(codex.codexId).collect { items ->
                            itemCounts[codex.codexId] = items.size
                        }
                    }
                    launch {
                        data.codexRepository.observeCodexPosts(codex.codexId, CodexSortMode.NEWEST_SAVED)
                            .collect { posts ->
                                coverModels[codex.codexId] = posts.firstOrNull()?.let { post ->
                                    resolveCodexCoverModel(data.storageDirectory, post)
                                }
                                if (availableSources != null) {
                                    val options = codexSearchSourceOptions(posts, availableSources)
                                    sourceOptions[codex.codexId] = options
                                    tagOptions[codex.codexId] = options.associate { option ->
                                        option.source to codexSearchTagOptions(posts, option.source)
                                    }
                                }
                            }
                    }
                }
            }
        }
    }
    return remember(itemCounts, coverModels, sourceOptions, tagOptions) {
        CodexCollectionState(
            itemCounts = itemCounts,
            coverModels = coverModels,
            searchSourceOptions = sourceOptions,
            searchTagOptions = tagOptions,
        )
    }
}

@Composable
private fun rememberSavedPostIds(data: DataDependencies): Set<PostId> {
    val codicesState = data.codexRepository.observeCodices()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val savedByCodex = remember { mutableStateMapOf<String, Set<PostId>>() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val codices = codicesState.value
    LaunchedEffect(lifecycleOwner, codices.map(Codex::codexId)) {
        val activeIds = codices.map(Codex::codexId).toSet()
        savedByCodex.keys.filterNot(activeIds::contains).toList().forEach(savedByCodex::remove)
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            coroutineScope {
                codices.forEach { codex ->
                    launch {
                        data.codexRepository.observeCodexItems(codex.codexId).collect { items ->
                            savedByCodex[codex.codexId] = items.mapTo(linkedSetOf()) { it.postId }
                        }
                    }
                }
            }
        }
    }
    val saved by remember { derivedStateOf { savedByCodex.values.flatten().toSet() } }
    return saved
}
