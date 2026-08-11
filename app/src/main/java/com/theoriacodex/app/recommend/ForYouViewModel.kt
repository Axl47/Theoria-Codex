package com.theoriacodex.app.recommend

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouCoordinatorSnapshot
import com.theoriacodex.app.recommend.state.ForYouEffect
import com.theoriacodex.app.recommend.state.ForYouRequestIdentity
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.app.recommend.state.reduce
import com.theoriacodex.app.recommend.state.toUiState
import com.theoriacodex.app.ui.state.RouteStateOwner
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.defaultRecommendationProfiles
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Non-Compose execution boundary used by the navigation-scoped route owner. */
internal interface ForYouRouteEngine {
    suspend fun initialize()
    fun restoreRouteInputs(source: SourceKey?, sort: SortMode?)
    fun snapshot(
        profiles: List<RecommendationProfile>,
        blacklistEntries: List<ForYouBlacklistEntry>,
    ): ForYouCoordinatorSnapshot

    fun onSettingsChanged(settings: AppSettings): Boolean
    fun onAvailableSourcesChanged(): Boolean
    suspend fun refresh(shuffle: Boolean)
    suspend fun selectProfile(settings: AppSettings, profileId: String)
    suspend fun setSourceSelection(source: SourceKey?)
    suspend fun setSortMode(sort: SortMode)
    suspend fun replaySearch(seedBySource: Map<SourceKey, List<String>>, sort: SortMode)
    suspend fun blacklistCurrentSeedAndRefresh(): List<ForYouBlacklistEntry>
    suspend fun undoBlacklistAndRefresh(profileId: String, entries: List<ForYouBlacklistEntry>)
    suspend fun loadNextPage()
    fun clear()
    suspend fun resolvePost(postId: PostId): Post?
    fun rememberResolvedPost(post: Post)
}

internal class CoordinatorForYouRouteEngine(
    private val coordinator: ForYouCoordinator,
) : ForYouRouteEngine {
    override suspend fun initialize() = coordinator.initialize()

    override fun restoreRouteInputs(source: SourceKey?, sort: SortMode?) {
        coordinator.restoreRouteInputs(source, sort)
    }

    override fun snapshot(
        profiles: List<RecommendationProfile>,
        blacklistEntries: List<ForYouBlacklistEntry>,
    ): ForYouCoordinatorSnapshot {
        return ForYouCoordinatorSnapshot(
            profiles = profiles,
            activeProfileId = coordinator.activeProfileId,
            activeProfileLikesCount = coordinator.activeProfileLikesCount,
            availableSources = coordinator.availableSourceSelections,
            selectedSource = coordinator.selectedSource,
            sortMode = coordinator.sortMode,
            seedId = coordinator.seedId,
            seedSummaryBySource = coordinator.seedSummaryBySource,
            blacklistEntries = blacklistEntries,
            results = coordinator.results,
            statuses = coordinator.statuses,
            loading = coordinator.loading,
            loadingMore = coordinator.loadingMore,
            canLoadMore = coordinator.canLoadMore,
            errorMessage = coordinator.errorMessage,
        )
    }

    override fun onSettingsChanged(settings: AppSettings): Boolean = coordinator.onSettingsChanged(settings)
    override fun onAvailableSourcesChanged(): Boolean = coordinator.onAvailableSourcesChanged()
    override suspend fun refresh(shuffle: Boolean) = coordinator.refresh(shuffle)

    override suspend fun selectProfile(settings: AppSettings, profileId: String) {
        coordinator.onSettingsChanged(settings.copy(activeProfileId = profileId))
        coordinator.refresh(shuffle = false)
    }

    override suspend fun setSourceSelection(source: SourceKey?) = coordinator.setSourceSelection(source)
    override suspend fun setSortMode(sort: SortMode) = coordinator.setSortMode(sort)
    override suspend fun replaySearch(seedBySource: Map<SourceKey, List<String>>, sort: SortMode) =
        coordinator.replaySearch(seedBySource, sort)
    override suspend fun blacklistCurrentSeedAndRefresh(): List<ForYouBlacklistEntry> =
        coordinator.blacklistCurrentSeedAndRefresh()

    override suspend fun undoBlacklistAndRefresh(profileId: String, entries: List<ForYouBlacklistEntry>) {
        coordinator.undoBlacklistAndRefresh(profileId, entries)
    }
    override suspend fun loadNextPage() = coordinator.loadNextPage()
    override fun clear() = coordinator.clear()
    override suspend fun resolvePost(postId: PostId): Post? = coordinator.resolvePostForFeed(postId)
    override fun rememberResolvedPost(post: Post) = coordinator.rememberResolvedPost(post)
}

/**
 * Navigation-scoped owner for recommendation state and work.
 *
 * Only compact route inputs are reconstructed from [SavedStateHandle]. Results and jobs remain
 * process-local. Internal engine commands are executed here; only user-facing one-shot effects
 * are published to the shell.
 */
internal class ForYouViewModel(
    private val engine: ForYouRouteEngine,
    private val savedStateHandle: SavedStateHandle,
    initialProfiles: List<RecommendationProfile> = defaultRecommendationProfiles(),
    initialSort: SortMode? = null,
    coroutineScope: CoroutineScope? = null,
) : ViewModel(), RouteStateOwner<ForYouUiState, ForYouAction, ForYouEffect> {
    constructor(
        coordinator: ForYouCoordinator,
        savedStateHandle: SavedStateHandle,
        initialProfiles: List<RecommendationProfile> = defaultRecommendationProfiles(),
        initialSort: SortMode? = null,
    ) : this(
        engine = CoordinatorForYouRouteEngine(coordinator),
        savedStateHandle = savedStateHandle,
        initialProfiles = initialProfiles,
        initialSort = initialSort,
    )

    private val ownerScope = coroutineScope ?: viewModelScope
    private val restoredSource = savedStateHandle.get<String>(KEY_SELECTED_SOURCE)
        ?.let { name -> SourceKey.entries.firstOrNull { it.name == name } }
    private val restoredSort = savedStateHandle.get<String>(KEY_SORT_MODE)
        ?.let { name -> SortMode.entries.firstOrNull { it.name == name } }
        ?: initialSort
    private val restoredProfileId = savedStateHandle.get<String>(KEY_PROFILE_ID)

    private var profiles = initialProfiles
    private var blacklistEntries: List<ForYouBlacklistEntry> = emptyList()
    private var latestSettings: AppSettings? = null
    private var rootJob: Job? = null
    private var pageJob: Job? = null
    private var seedMutationJob: Job? = null
    private var environmentSynchronized = false
    private var sourceAvailabilitySynchronized = false
    private var pendingReplaySearch: ForYouAction.ReplaySearch? = null

    private val mutableState = MutableStateFlow(
        engine.snapshot(initialProfiles, emptyList()).toUiState().copy(
            activeProfileId = restoredProfileId
                ?.takeIf { id -> initialProfiles.any { profile -> profile.profileId == id } }
                ?: initialProfiles.firstOrNull()?.profileId
                ?: defaultRecommendationProfiles().first().profileId,
            selectedSource = restoredSource,
            sortMode = restoredSort ?: SortMode.NEWEST,
        )
    )
    override val state: StateFlow<ForYouUiState> = mutableState.asStateFlow()

    private val effectChannel = Channel<ForYouEffect>(capacity = Channel.BUFFERED)
    override val effects: Flow<ForYouEffect> = effectChannel.receiveAsFlow()
    init {
        ownerScope.launch {
            engine.initialize()
            engine.restoreRouteInputs(restoredSource, restoredSort)
            publishSnapshot(activeLikesOverride = mutableState.value.activeProfileLikesCount)
        }
    }

    override fun onAction(action: ForYouAction) {
        if (action is ForYouAction.ReplaySearch && !isReplayReady()) {
            pendingReplaySearch = action
            return
        }
        val transition = mutableState.value.reduce(action)
        mutableState.value = transition.state
        persistRouteInputs(transition.state)
        transition.effect?.let(::handleEffect)
    }

    /** Synchronizes app-level settings/profile inputs without making the screen own refresh jobs. */
    fun synchronizeEnvironment(
        settings: AppSettings,
        activeProfileLikesCount: Int,
    ) {
        profiles = settings.recommendationProfiles
        blacklistEntries = settings.forYouBlacklistByProfile[settings.activeProfileId].orEmpty()
        latestSettings = settings
        val previous = mutableState.value
        val engineChanged = engine.onSettingsChanged(settings)
        if (engineChanged) {
            cancelActiveReduction()
        }

        if (activeProfileLikesCount <= 0) {
            cancelActiveReduction()
            engine.clear()
            publishSnapshot(activeLikesOverride = 0)
            environmentSynchronized = true
            replayPendingSearchIfReady()
            return
        }

        publishSnapshot(activeLikesOverride = activeProfileLikesCount)
        environmentSynchronized = true
        if (pendingReplaySearch != null) {
            replayPendingSearchIfReady()
            return
        }
        val current = mutableState.value
        val shouldRefresh = engineChanged ||
            current.results.isEmpty() ||
            previous.activeProfileId != settings.activeProfileId ||
            previous.activeProfileLikesCount != activeProfileLikesCount
        if (shouldRefresh && !current.isRefreshing && !current.isPaging) {
            onAction(ForYouAction.Refresh(shuffle = false))
        }
    }

    private fun isReplayReady(): Boolean = environmentSynchronized && sourceAvailabilitySynchronized

    private fun replayPendingSearchIfReady(): Boolean {
        if (!isReplayReady()) return false
        val pending = pendingReplaySearch ?: return false
        pendingReplaySearch = null
        onAction(pending)
        return true
    }

    /** Reconciles a stable engine after credential/source capability changes. */
    fun onSourceAvailabilityChanged() {
        val shouldRefresh = engine.onAvailableSourcesChanged()
        if (shouldRefresh) {
            cancelActiveReduction()
        }
        publishSnapshot(activeLikesOverride = mutableState.value.activeProfileLikesCount)
        sourceAvailabilitySynchronized = true
        if (pendingReplaySearch != null) {
            replayPendingSearchIfReady()
            return
        }
        if (shouldRefresh && mutableState.value.activeProfileLikesCount > 0) {
            onAction(ForYouAction.Refresh(shuffle = false))
        }
    }

    suspend fun resolvePost(postId: PostId): Post? {
        return engine.resolvePost(postId)?.also {
            publishSnapshot(activeLikesOverride = mutableState.value.activeProfileLikesCount)
        }
    }

    fun rememberResolvedPost(post: Post) {
        engine.rememberResolvedPost(post)
        publishSnapshot(activeLikesOverride = mutableState.value.activeProfileLikesCount)
    }

    private fun handleEffect(effect: ForYouEffect) {
        when (effect) {
            is ForYouEffect.RefreshFeed -> launchRefresh(effect.request) {
                engine.refresh(effect.shuffle)
            }

            is ForYouEffect.ChangeProfile -> {
                val settings = latestSettings
                if (settings == null) {
                    onAction(ForYouAction.RefreshFailed(effect.request, "Recommendation settings unavailable"))
                } else {
                    launchRefresh(effect.request) {
                        engine.selectProfile(settings, effect.profileId)
                    }
                }
            }

            is ForYouEffect.ChangeSource -> launchRefresh(effect.request) {
                engine.setSourceSelection(effect.source)
            }

            is ForYouEffect.ChangeSort -> launchRefresh(effect.request) {
                engine.setSortMode(effect.sortMode)
            }

            is ForYouEffect.ReplaySearch -> launchRefresh(effect.request) {
                engine.replaySearch(effect.seedBySource, effect.sortMode)
            }

            is ForYouEffect.BlacklistSeed -> launchSeedMutation(effect.request) {
                val additions = engine.blacklistCurrentSeedAndRefresh()
                effectChannel.trySend(
                    if (additions.isNotEmpty()) {
                        ForYouEffect.SeedHidden(
                            profileId = effect.profileId,
                            entries = additions,
                        )
                    } else {
                        ForYouEffect.ShowMessage(
                            "Current recommendation is already hidden",
                        )
                    }
                )
            }

            is ForYouEffect.UndoSeedBlacklist -> launchSeedMutation(effect.request) {
                engine.undoBlacklistAndRefresh(effect.profileId, effect.entries)
            }

            is ForYouEffect.LoadNextPage -> launchPage(effect.request)
            is ForYouEffect.OpenViewer,
            is ForYouEffect.SeedHidden,
            ForYouEffect.NavigateToSearch,
            is ForYouEffect.ShowMessage,
            -> effectChannel.trySend(effect)
        }
    }

    private fun launchRefresh(
        request: ForYouRequestIdentity,
        operation: suspend () -> Unit,
    ) {
        rootJob?.cancel(CancellationException("For You refresh replaced"))
        pageJob?.cancel(CancellationException("For You page replaced by refresh"))
        rootJob = ownerScope.launch {
            try {
                operation()
                onAction(ForYouAction.RefreshCompleted(request, snapshot()))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onAction(ForYouAction.RefreshFailed(request, error.message ?: "Could not load recommendations"))
            } finally {
                if (!isActive) onAction(ForYouAction.RequestCancelled(request))
            }
        }
    }

    private fun launchSeedMutation(
        request: ForYouRequestIdentity,
        operation: suspend () -> Unit,
    ) {
        seedMutationJob?.cancel(CancellationException("For You seed mutation replaced"))
        seedMutationJob = ownerScope.launch {
            try {
                operation()
                onAction(ForYouAction.RefreshCompleted(request, snapshot()))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onAction(ForYouAction.RefreshFailed(request, error.message ?: "Could not update recommendations"))
            } finally {
                if (!isActive) onAction(ForYouAction.RequestCancelled(request))
            }
        }
    }

    private fun launchPage(request: ForYouRequestIdentity) {
        pageJob?.cancel(CancellationException("For You page replaced"))
        pageJob = ownerScope.launch {
            try {
                engine.loadNextPage()
                val snapshot = snapshot()
                val error = snapshot.errorMessage
                if (error == null) {
                    onAction(
                        ForYouAction.PageLoaded(
                            request = request,
                            posts = snapshot.results,
                            statuses = snapshot.statuses,
                            canLoadMore = snapshot.canLoadMore,
                        )
                    )
                } else {
                    onAction(ForYouAction.PageFailed(request, error))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onAction(ForYouAction.PageFailed(request, error.message ?: "Could not load more recommendations"))
            } finally {
                if (!isActive) onAction(ForYouAction.RequestCancelled(request))
            }
        }
    }

    private fun snapshot(): ForYouCoordinatorSnapshot = engine.snapshot(profiles, blacklistEntries)

    private fun publishSnapshot(activeLikesOverride: Int? = null) {
        val previous = mutableState.value
        val snapshot = snapshot()
        mutableState.value = snapshot.toUiState().copy(
            activeProfileLikesCount = activeLikesOverride ?: snapshot.activeProfileLikesCount,
            nextRequestGeneration = previous.nextRequestGeneration,
            activeRequest = previous.activeRequest,
            isRefreshing = previous.isRefreshing,
            isPaging = previous.isPaging,
        )
        persistRouteInputs(mutableState.value)
    }

    private fun persistRouteInputs(value: ForYouUiState) {
        savedStateHandle[KEY_PROFILE_ID] = value.activeProfileId
        savedStateHandle[KEY_SELECTED_SOURCE] = value.selectedSource?.name
        savedStateHandle[KEY_SORT_MODE] = value.sortMode.name
    }

    private fun cancelRequests() {
        rootJob?.cancel(CancellationException("For You environment cleared"))
        pageJob?.cancel(CancellationException("For You environment cleared"))
        rootJob = null
        pageJob = null
    }

    private fun cancelActiveReduction() {
        val request = mutableState.value.activeRequest
        cancelRequests()
        if (request != null) {
            onAction(ForYouAction.RequestCancelled(request))
        }
    }

    companion object {
        fun factory(
            coordinator: ForYouCoordinator,
            initialProfiles: List<RecommendationProfile> = defaultRecommendationProfiles(),
            initialSort: SortMode? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ForYouViewModel(
                    coordinator = coordinator,
                    savedStateHandle = createSavedStateHandle(),
                    initialProfiles = initialProfiles,
                    initialSort = initialSort,
                )
            }
        }

        internal const val KEY_PROFILE_ID = "for_you_profile_id"
        internal const val KEY_SELECTED_SOURCE = "for_you_selected_source"
        internal const val KEY_SORT_MODE = "for_you_sort_mode"
    }
}
