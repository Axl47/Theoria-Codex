package com.theoriacodex.app.recommend

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouEffect
import com.theoriacodex.app.recommend.state.ForYouRequestIdentity
import com.theoriacodex.app.recommend.state.ForYouRequestKind
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.app.recommend.state.reduce
import com.theoriacodex.app.recommend.state.ForYouEmptyReason
import com.theoriacodex.app.media.replacingPostSnapshot
import com.theoriacodex.app.related.promoteRelatedPost
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.orchestration.UnifiedSearchResult
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.UnsupportedRelatedPostsLoader
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** One navigation-scoped owner for recommendation inputs, admission, continuation, and work. */
internal class ForYouViewModel(
    private val engine: ForYouRouteEngine,
    private val savedStateHandle: SavedStateHandle,
    initialProfiles: List<RecommendationProfile> = defaultRecommendationProfiles(),
    initialSort: SortMode? = null,
    coroutineScope: CoroutineScope? = null,
    relatedPostsLoader: RelatedPostsLoading = UnsupportedRelatedPostsLoader,
) : ViewModel(), RouteStateOwner<ForYouUiState, ForYouAction, ForYouEffect> {
    constructor(
        coordinator: ForYouCoordinator,
        savedStateHandle: SavedStateHandle,
        initialProfiles: List<RecommendationProfile> = defaultRecommendationProfiles(),
        initialSort: SortMode? = null,
        relatedPostsLoader: RelatedPostsLoading = UnsupportedRelatedPostsLoader,
    ) : this(coordinator as ForYouRouteEngine, savedStateHandle, initialProfiles, initialSort,
        relatedPostsLoader = relatedPostsLoader)

    private val ownerScope = coroutineScope ?: viewModelScope
    private var latestSettings: AppSettings? = null
    private var requestJob: Job? = null
    private var seedMutationRequest: ForYouRequestIdentity? = null
    private var environmentSynchronized = false
    private var sourceAvailabilitySynchronized = false
    private var pendingReplaySearch: ForYouAction.ReplaySearch? = null
    private var replaySearch: ForYouAction.ReplaySearch? = null
    private var hasRequestedFeed = false
    private val mutableState = MutableStateFlow(ForYouUiState(
        home = ForYouHomeState(visible = true),
        profiles = initialProfiles,
        activeProfileId = savedStateHandle.get<String>(KEY_PROFILE_ID)
            ?.takeIf { id -> initialProfiles.any { it.profileId == id } }
            ?: initialProfiles.firstOrNull()?.profileId ?: defaultRecommendationProfiles().first().profileId,
        selectedSource = savedStateHandle.get<String>(KEY_SELECTED_SOURCE)
            ?.let { name -> SourceKey.entries.firstOrNull { it.name == name } },
        sortMode = savedStateHandle.get<String>(KEY_SORT_MODE)
            ?.let { name -> SortMode.entries.firstOrNull { it.name == name } } ?: initialSort ?: SortMode.NEWEST,
    ))
    private val relatedDiagnostics = com.theoriacodex.app.related.RelatedPostsDiagnostics("ForYou", this)
    private val relatedPosts = RelatedPostsRouteController(
        scope = ownerScope,
        loader = relatedPostsLoader,
        currentState = { mutableState.value.relatedPosts },
        canonicalPosts = { mutableState.value.results },
        promotePost = { post, index ->
            val current = mutableState.value
            mutableState.value = current.copy(results = promoteRelatedPost(current.results, post, index))
        },
        updateState = { related ->
            relatedDiagnostics.record("state updated", related)
            mutableState.value = mutableState.value.copy(relatedPosts = related)
        },
        onClear = { state, reason -> relatedDiagnostics.record("clear: $reason", state) },
    )
    init {
        relatedDiagnostics.record("owner created", mutableState.value.relatedPosts)
        addCloseable("related-posts-diagnostics", java.io.Closeable {
            relatedDiagnostics.record("owner cleared", mutableState.value.relatedPosts)
        })
    }
    override val state: StateFlow<ForYouUiState> = mutableState.asStateFlow()
    private val effectChannel = Channel<ForYouEffect>(capacity = Channel.BUFFERED)
    override val effects: Flow<ForYouEffect> = effectChannel.receiveAsFlow()

    private val home = ForYouHomeController(
        scope = ownerScope, engine = engine, state = { mutableState.value },
        update = { value -> mutableState.value = mutableState.value.copy(home = value) },
        request = {
            latestSettings?.let { settings ->
                val current = mutableState.value
                ForYouFeedRequest(settings.copy(activeProfileId = current.activeProfileId), current.availableSources,
                    current.selectedSource, current.sortMode, false, current.activeProfileLikesCount)
            }
        },
        admit = ::admitPreview,
        emit = { effectChannel.trySend(it) },
        settingsChanged = { settings -> synchronizeEnvironment(settings, mutableState.value.activeProfileLikesCount) },
    )

    private fun admitPreview(result: ForYouFeedResult) {
        cancelActiveReduction()
        relatedPosts.clear("interest selected")
        val current = mutableState.value
        mutableState.value = current.copy(
            results = result.page.items, statuses = result.page.statuses, continuation = result.continuation,
            seedId = result.seedId, seedSummaryBySource = result.seed,
            isRefreshing = false, isPaging = false, errorMessage = null,
            emptyReason = if (result.page.items.isEmpty()) ForYouEmptyReason.NO_RESULTS else null,
        )
        hasRequestedFeed = true
        replaySearch = null
        ownerScope.launch { runCatchingPreservingCancellation { engine.recordAccepted(result) } }
    }

    override fun onAction(action: ForYouAction) {
        if (action is ForYouAction.OpenSectionResult) {
            openSectionPreview(action)
            return
        }
        if (home.handle(action)) return
        when (action) {
            is ForYouAction.RelatedLikeCommitted -> {
                relatedPosts.onLikeCommitted(action.post, action.outcome)
                return
            }
            ForYouAction.RetryRelatedPosts -> {
                relatedPosts.retry()
                return
            }
            ForYouAction.DismissRelatedPosts -> {
                relatedPosts.clear()
                return
            }
            ForYouAction.ShowPreviousRelatedPosts -> {
                relatedPosts.showPreviousPage()
                return
            }
            ForYouAction.ShowNextRelatedPosts -> {
                relatedPosts.showNextPage()
                return
            }
            else -> Unit
        }
        if (action is ForYouAction.ReplaySearch && !(environmentSynchronized && sourceAvailabilitySynchronized)) {
            pendingReplaySearch = action
            return
        }
        val previous = mutableState.value
        if (action is ForYouAction.ReplaySearch) {
            mutableState.value = previous.copy(home = previous.home.copy(visible = false, activeSection = null))
        }
        val reduced = mutableState.value.reduce(action)
        val startsNewScope = reduced.effect is ForYouEffect.ChangeSource ||
            reduced.effect is ForYouEffect.ChangeProfile || reduced.effect is ForYouEffect.BlacklistSeed
        val transition = if (startsNewScope) reduced.copy(
            state = reduced.state.copy(home = reduced.state.home.copy(activeSection = null)),
        ) else reduced
        if (transition.state.activeRequest?.kind == ForYouRequestKind.REFRESH &&
            transition.state.activeRequest != previous.activeRequest
        ) {
            relatedPosts.clear("recommendation root (${action.javaClass.simpleName})")
            hasRequestedFeed = true
            replaySearch = action as? ForYouAction.ReplaySearch
        }
        mutableState.value = transition.state
        persistRouteInputs(transition.state)
        transition.effect?.let(::handleEffect)
        if (action is ForYouAction.SelectSource || action is ForYouAction.SelectSort) home.synchronize()
    }

    private fun openSectionPreview(action: ForYouAction.OpenSectionResult) {
        if (action.index !in action.visibleResults.indices) return
        val current = mutableState.value.home
        val section = action.sectionId?.let { id -> current.sections.firstOrNull { it.id == id } }
        if (action.sectionId != null && section == null) return
        if (action.sectionId == null && current.mixedFeed == null) return
        val select = action.sectionId?.let { ForYouAction.OpenSection(it) } ?: ForYouAction.OpenMixedFeed
        home.handle(select)
        home.handle(ForYouAction.ShowHome)
        onAction(ForYouAction.OpenResult(action.index, visibleResults = action.visibleResults,
            visibilityFilters = action.visibilityFilters))
    }

    /** A count-only Like update never regenerates an admitted feed. */
    fun synchronizeEnvironment(settings: AppSettings, activeProfileLikesCount: Int) {
        val previousSettings = latestSettings
        val previous = mutableState.value
        val profileChanged = previous.activeProfileId != settings.activeProfileId
        val runtimeChanged = previousSettings != null && previousSettings.runtime != settings.runtime
        val blacklistChanged = previousSettings != null &&
            previousSettings.forYouBlacklistByProfile[settings.activeProfileId] !=
            settings.forYouBlacklistByProfile[settings.activeProfileId]
        val ownBlacklistWrite = seedMutationRequest != null && seedMutationRequest == previous.activeRequest
        latestSettings = settings
        environmentSynchronized = true
        val available = engine.availableSources(settings)
        val sourcesChanged = previous.availableSources != available
        val refreshRequired = profileChanged || runtimeChanged || sourcesChanged || (blacklistChanged && !ownBlacklistWrite)
        relatedDiagnostics.record(
            "environment: profile=$profileChanged runtime=$runtimeChanged sources=$sourcesChanged blacklist=$blacklistChanged",
            previous.relatedPosts,
            enabled = refreshRequired,
        )
        if (refreshRequired) cancelActiveReduction()
        if (profileChanged) {
            hasRequestedFeed = false
            replaySearch = null
            relatedPosts.clear("active profile changed")
        }
        val current = mutableState.value
        mutableState.value = current.copy(
            profiles = settings.recommendationProfiles,
            activeProfileId = settings.activeProfileId,
            activeProfileLikesCount = activeProfileLikesCount.coerceAtLeast(0),
            blacklistEntries = settings.forYouBlacklistByProfile[settings.activeProfileId].orEmpty(),
            availableSources = available,
            selectedSource = current.selectedSource?.takeIf { it in available },
            results = if (profileChanged) emptyList() else current.results.filter { it.id.source in available },
            continuation = if (refreshRequired) null else current.continuation,
            seedSummaryBySource = if (profileChanged) emptyMap() else current.seedSummaryBySource,
        )
        home.synchronize()
        if (pendingReplaySearch != null) {
            replayPendingSearchIfReady()
            return
        }
        if (!ownBlacklistWrite) refreshForEnvironment(refreshRequired)
    }

    fun onSourceAvailabilityChanged() {
        sourceAvailabilitySynchronized = true
        val settings = latestSettings ?: return
        val available = engine.availableSources(settings)
        val changed = mutableState.value.availableSources != available
        if (changed) {
            relatedDiagnostics.record("available sources changed", mutableState.value.relatedPosts)
            cancelActiveReduction()
            val current = mutableState.value
            mutableState.value = current.copy(
                availableSources = available,
                selectedSource = current.selectedSource?.takeIf { it in available },
                results = current.results.filter { it.id.source in available },
                continuation = null,
            )
        }
        home.synchronize()
        if (pendingReplaySearch != null) replayPendingSearchIfReady() else refreshForEnvironment(changed)
    }

    private fun replayPendingSearchIfReady() {
        if (!environmentSynchronized || !sourceAvailabilitySynchronized) return
        val pending = pendingReplaySearch ?: return
        pendingReplaySearch = null
        onAction(pending)
    }

    private fun refreshForEnvironment(changed: Boolean) {
        val current = mutableState.value
        if (current.isRefreshing || current.isPaging || (!changed && hasRequestedFeed)) return
        val historical = replaySearch
        if (historical != null) {
            onAction(historical)
        } else if (current.activeProfileLikesCount > 0) {
            onAction(ForYouAction.Refresh(shuffle = false))
        } else if (!hasRequestedFeed) {
            mutableState.value = current.copy(seedId = "empty", emptyReason = ForYouEmptyReason.NO_LIKES)
        }
    }

    suspend fun resolvePost(postId: PostId): Post? = engine.resolvePost(postId)?.also(::rememberResolvedPost)

    fun rememberResolvedPost(post: Post) {
        val current = mutableState.value
        mutableState.value = current.copy(results = current.results.replacingPostSnapshot(post))
    }

    private fun handleEffect(effect: ForYouEffect) {
        when (effect) {
            is ForYouEffect.RefreshFeed -> launchRefresh(effect.request, effect.shuffle)
            is ForYouEffect.ChangeProfile -> launchRefresh(effect.request, false)
            is ForYouEffect.ChangeSource -> launchRefresh(effect.request, false)
            is ForYouEffect.ChangeSort -> launchRefresh(effect.request, false)
            is ForYouEffect.ReplaySearch -> launchRefresh(effect.request, false, effect.seedBySource)
            is ForYouEffect.BlacklistSeed -> launchSeedMutation(
                effect.request, effect.profileId,
                effect.seedSummaryBySource.map { (source, tags) -> ForYouBlacklistEntry(source, tags) }, true,
            )
            is ForYouEffect.UndoSeedBlacklist -> launchSeedMutation(effect.request, effect.profileId, effect.entries, false)
            is ForYouEffect.LoadNextPage -> launchPage(effect)
            is ForYouEffect.OpenViewer, is ForYouEffect.SeedHidden,
            ForYouEffect.NavigateToSearch, is ForYouEffect.ShowMessage -> effectChannel.trySend(effect)
        }
    }

    private fun feedRequest(identity: ForYouRequestIdentity, shuffle: Boolean, seed: Map<SourceKey, List<String>>?) =
        ForYouFeedRequest(
            settings = requireNotNull(latestSettings) { "Recommendation settings unavailable" }
                .copy(activeProfileId = identity.profileId),
            availableSources = mutableState.value.availableSources,
            selectedSource = identity.source,
            sortMode = identity.sortMode,
            shuffle = shuffle,
            likesCount = mutableState.value.activeProfileLikesCount,
            historicalSeed = seed,
        )

    private fun launchRefresh(
        identity: ForYouRequestIdentity,
        shuffle: Boolean,
        seed: Map<SourceKey, List<String>>? = null,
    ) = launchRequest(identity) {
        val scopedSeed = seed ?: mutableState.value.home.activeSection?.feed?.seed
        acceptRoot(identity, engine.execute(feedRequest(identity, shuffle, scopedSeed)))
    }

    private suspend fun acceptRoot(identity: ForYouRequestIdentity, result: ForYouFeedResult) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (mutableState.value.activeRequest != identity) return
        onAction(ForYouAction.RefreshCompleted(identity, result))
        val current = mutableState.value
        val selected = current.home.activeSection
        mutableState.value = current.copy(home = if (selected == null) {
            current.home.copy(mixedFeed = result)
        } else {
            current.home.copy(activeSection = selected.copy(feed = result))
        })
        // A later refresh cannot cancel bookkeeping for an already accepted root.
        ownerScope.launch { runCatchingPreservingCancellation { engine.recordAccepted(result) } }
    }

    private fun launchSeedMutation(
        identity: ForYouRequestIdentity,
        profileId: String,
        entries: List<ForYouBlacklistEntry>,
        hidden: Boolean,
    ) {
        seedMutationRequest = identity
        launchRequest(identity) {
            val changed = engine.updateBlacklist(profileId, entries, hidden)
            if (hidden) effectChannel.trySend(
                if (changed.entries.isEmpty()) ForYouEffect.ShowMessage("Current recommendation is already hidden")
                else ForYouEffect.SeedHidden(profileId, changed.entries)
            )
            if (mutableState.value.activeRequest != identity) return@launchRequest
            latestSettings = changed.settings
            home.synchronize()
            mutableState.value = mutableState.value.copy(
                blacklistEntries = changed.settings.forYouBlacklistByProfile[identity.profileId].orEmpty(),
            )
            acceptRoot(identity, engine.execute(feedRequest(identity, true, null)))
        }
    }

    private fun launchPage(effect: ForYouEffect.LoadNextPage) = launchRequest(effect.request, effect.sources) {
        val result = engine.loadPage(effect.continuation, effect.sources)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        onAction(ForYouAction.PageLoaded(effect.request, result, effect.sources))
    }

    private fun launchRequest(
        identity: ForYouRequestIdentity,
        attemptedSources: Set<SourceKey> = emptySet(),
        operation: suspend () -> Unit,
    ) {
        requestJob?.cancel(CancellationException("For You request replaced"))
        requestJob = ownerScope.launch {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val message = error.message ?: "Could not load recommendations"
                if (identity.kind == ForYouRequestKind.REFRESH) {
                    onAction(ForYouAction.RefreshFailed(identity, message))
                } else {
                    onAction(ForYouAction.PageLoaded(identity, UnifiedSearchResult(
                        emptyList(), emptyMap(), attemptedSources.map {
                            SourceRunStatus(it, SourceRunState.FAILED, errorMessage = message)
                        },
                    ), attemptedSources))
                }
            } finally {
                if (!isActive) onAction(ForYouAction.RequestCancelled(identity))
                if (seedMutationRequest == identity) seedMutationRequest = null
            }
        }
    }

    private fun cancelActiveReduction() {
        requestJob?.cancel(CancellationException("For You environment changed"))
        mutableState.value.activeRequest?.let { onAction(ForYouAction.RequestCancelled(it)) }
    }

    private fun persistRouteInputs(value: ForYouUiState) {
        savedStateHandle[KEY_PROFILE_ID] = value.activeProfileId
        savedStateHandle[KEY_SELECTED_SOURCE] = value.selectedSource?.name
        savedStateHandle[KEY_SORT_MODE] = value.sortMode.name
    }

    companion object {
        fun factory(
            coordinator: ForYouCoordinator,
            initialProfiles: List<RecommendationProfile> = defaultRecommendationProfiles(),
            initialSort: SortMode? = null,
            relatedPostsLoader: RelatedPostsLoading = UnsupportedRelatedPostsLoader,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ForYouViewModel(
                    coordinator = coordinator,
                    savedStateHandle = createSavedStateHandle(),
                    initialProfiles = initialProfiles,
                    initialSort = initialSort,
                    relatedPostsLoader = relatedPostsLoader,
                )
            }
        }

        internal const val KEY_PROFILE_ID = "for_you_profile_id"
        internal const val KEY_SELECTED_SOURCE = "for_you_selected_source"
        internal const val KEY_SORT_MODE = "for_you_sort_mode"
    }
}
