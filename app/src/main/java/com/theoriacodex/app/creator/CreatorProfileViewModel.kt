package com.theoriacodex.app.creator

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.media.AnimatedDurationEnricher
import com.theoriacodex.app.media.AnimatedDurationEnrichment
import com.theoriacodex.app.media.AnimatedDurationEnrichmentLane
import com.theoriacodex.app.media.NoOpAnimatedDurationEnricher
import com.theoriacodex.app.media.animatedDurationMs
import com.theoriacodex.app.creator.state.CreatorAction
import com.theoriacodex.app.creator.state.CreatorCoordinatorSnapshot
import com.theoriacodex.app.creator.state.CreatorEffect
import com.theoriacodex.app.creator.state.CreatorFailureReason
import com.theoriacodex.app.creator.state.CreatorRequestIdentity
import com.theoriacodex.app.creator.state.CreatorUiState
import com.theoriacodex.app.creator.state.reduce
import com.theoriacodex.app.creator.state.toUiState
import com.theoriacodex.app.ui.state.RouteStateOwner
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
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

/** Non-Compose execution boundary used by the Creator route owner. */
internal interface CreatorRouteEngine {
    fun snapshot(): CreatorCoordinatorSnapshot
    suspend fun open(creator: CreatorProfile)
    suspend fun refresh()
    suspend fun loadNextPage()
    fun onAvailableSourcesChanged(): Boolean
    suspend fun resolvePost(postId: PostId): Post?
    fun rememberResolvedPost(post: Post)
}

internal class CoordinatorCreatorRouteEngine(
    private val coordinator: CreatorProfileCoordinator,
) : CreatorRouteEngine {
    override fun snapshot(): CreatorCoordinatorSnapshot {
        val error = coordinator.errorMessage
        return CreatorCoordinatorSnapshot(
            creator = coordinator.activeCreator,
            queryHash = coordinator.activeQueryHash,
            results = coordinator.results,
            loading = coordinator.loading,
            loadingMore = coordinator.loadingMore,
            canLoadMore = coordinator.canLoadMore,
            errorMessage = error,
            failureReason = if (error?.startsWith("Creator browsing is not available") == true) {
                CreatorFailureReason.UNSUPPORTED_SOURCE
            } else if (error != null) {
                CreatorFailureReason.REQUEST_FAILED
            } else {
                null
            },
        )
    }

    override suspend fun open(creator: CreatorProfile) = coordinator.open(creator)
    override suspend fun refresh() = coordinator.refresh()
    override suspend fun loadNextPage() = coordinator.loadNextPage()
    override fun onAvailableSourcesChanged(): Boolean = coordinator.onAvailableSourcesChanged()
    override suspend fun resolvePost(postId: PostId): Post? = coordinator.resolvePostForCreator(postId)
    override fun rememberResolvedPost(post: Post) = coordinator.rememberResolvedPost(post)
}

/** Navigation-scoped owner for Creator identity, requests, paging, and navigation effects. */
internal class CreatorProfileViewModel(
    private val engine: CreatorRouteEngine,
    private val savedStateHandle: SavedStateHandle,
    coroutineScope: CoroutineScope? = null,
    private val animatedDurationEnricher: AnimatedDurationEnricher = NoOpAnimatedDurationEnricher,
) : ViewModel(), RouteStateOwner<CreatorUiState, CreatorAction, CreatorEffect> {
    constructor(
        coordinator: CreatorProfileCoordinator,
        savedStateHandle: SavedStateHandle,
        animatedDurationEnricher: AnimatedDurationEnricher = NoOpAnimatedDurationEnricher,
    ) : this(
        engine = CoordinatorCreatorRouteEngine(coordinator),
        savedStateHandle = savedStateHandle,
        animatedDurationEnricher = animatedDurationEnricher,
    )

    private val ownerScope = coroutineScope ?: viewModelScope
    private var rootJob: Job? = null
    private var pageJob: Job? = null

    private val mutableState = MutableStateFlow(engine.snapshot().toUiState())
    override val state: StateFlow<CreatorUiState> = mutableState.asStateFlow()

    private val effectChannel = Channel<CreatorEffect>(capacity = Channel.BUFFERED)
    override val effects: Flow<CreatorEffect> = effectChannel.receiveAsFlow()
    private val durationEnrichmentLane = AnimatedDurationEnrichmentLane(
        scope = ownerScope,
        enricher = animatedDurationEnricher,
        currentIdentity = { mutableState.value.queryHash },
        currentPosts = { mutableState.value.results },
        applyEnrichments = ::applyAnimatedDurationEnrichments,
    )

    init {
        restoredCreator()?.let { creator -> onAction(CreatorAction.OpenCreator(creator)) }
    }

    override fun onAction(action: CreatorAction) {
        if (action is CreatorAction.RequestAnimatedDurationEnrichment) {
            requestAnimatedDurationEnrichment(action.queryHash)
            return
        }
        val transition = mutableState.value.reduce(action)
        mutableState.value = transition.state
        transition.state.creator?.let(::persistCreator)
        transition.effect?.let(::handleEffect)
    }

    /** Reconciles the current creator when credential/source capability changes. */
    fun onSourceAvailabilityChanged() {
        val shouldRefresh = engine.onAvailableSourcesChanged()
        val previous = mutableState.value
        val snapshot = engine.snapshot()
        mutableState.value = snapshot.toUiState().copy(
            nextRequestGeneration = previous.nextRequestGeneration,
            activeRequest = null,
        )
        rootJob?.cancel(CancellationException("Creator capabilities changed"))
        pageJob?.cancel(CancellationException("Creator capabilities changed"))
        if (shouldRefresh && snapshot.creator != null) {
            onAction(CreatorAction.Refresh)
        }
    }

    suspend fun resolvePost(postId: PostId): Post? {
        return engine.resolvePost(postId)?.also { publishSnapshot() }
    }

    fun rememberResolvedPost(post: Post) {
        engine.rememberResolvedPost(post)
        publishSnapshot()
    }

    private fun requestAnimatedDurationEnrichment(queryHash: String) {
        durationEnrichmentLane.request(queryHash)
    }

    private fun applyAnimatedDurationEnrichments(
        queryHash: String,
        enrichments: List<AnimatedDurationEnrichment>,
    ) {
        if (mutableState.value.queryHash != queryHash) return
        var changed = false
        enrichments.forEach { result ->
            val latestPost = mutableState.value.results.firstOrNull { post -> post.id == result.postId }
                ?: return@forEach
            if (animatedDurationMs(latestPost) != null) return@forEach
            engine.rememberResolvedPost(latestPost.copy(durationMs = result.durationMs))
            changed = true
        }
        if (changed) publishSnapshot()
    }

    private fun handleEffect(effect: CreatorEffect) {
        when (effect) {
            is CreatorEffect.LoadCreator -> launchRefresh(effect.request) {
                engine.open(effect.creator)
            }

            is CreatorEffect.Refresh -> launchRefresh(effect.request) {
                engine.refresh()
            }

            is CreatorEffect.LoadNextPage -> launchPage(effect.request)
            is CreatorEffect.OpenViewer,
            CreatorEffect.NavigateBack,
            -> effectChannel.trySend(effect)
        }
    }

    private fun launchRefresh(
        request: CreatorRequestIdentity,
        operation: suspend () -> Unit,
    ) {
        rootJob?.cancel(CancellationException("Creator request replaced"))
        pageJob?.cancel(CancellationException("Creator page replaced by refresh"))
        rootJob = ownerScope.launch {
            try {
                operation()
                val snapshot = engine.snapshot()
                val error = snapshot.errorMessage
                if (error == null) {
                    onAction(CreatorAction.RefreshCompleted(request, snapshot))
                } else {
                    onAction(
                        CreatorAction.RefreshFailed(
                            request = request,
                            message = error,
                            reason = snapshot.failureReason ?: CreatorFailureReason.REQUEST_FAILED,
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onAction(
                    CreatorAction.RefreshFailed(
                        request = request,
                        message = error.message ?: "Could not load creator uploads",
                    )
                )
            } finally {
                if (!isActive) onAction(CreatorAction.RequestCancelled(request))
            }
        }
    }

    private fun launchPage(request: CreatorRequestIdentity) {
        pageJob?.cancel(CancellationException("Creator page replaced"))
        pageJob = ownerScope.launch {
            try {
                engine.loadNextPage()
                val snapshot = engine.snapshot()
                val error = snapshot.errorMessage
                if (error == null) {
                    onAction(
                        CreatorAction.PageLoaded(
                            request = request,
                            posts = snapshot.results,
                            canLoadMore = snapshot.canLoadMore,
                        )
                    )
                } else {
                    onAction(
                        CreatorAction.PageFailed(
                            request = request,
                            message = error,
                            reason = snapshot.failureReason ?: CreatorFailureReason.REQUEST_FAILED,
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onAction(
                    CreatorAction.PageFailed(
                        request = request,
                        message = error.message ?: "Could not load more creator uploads",
                    )
                )
            } finally {
                if (!isActive) onAction(CreatorAction.RequestCancelled(request))
            }
        }
    }

    private fun publishSnapshot() {
        val previous = mutableState.value
        mutableState.value = engine.snapshot().toUiState().copy(
            nextRequestGeneration = previous.nextRequestGeneration,
            activeRequest = previous.activeRequest,
            isRefreshing = previous.isRefreshing,
            isPaging = previous.isPaging,
        )
    }

    private fun persistCreator(creator: CreatorProfile) {
        savedStateHandle[KEY_SOURCE] = creator.source.name
        savedStateHandle[KEY_DISPLAY_NAME] = creator.displayName
        savedStateHandle[KEY_PROFILE_ID] = creator.profileId
        savedStateHandle[KEY_PROFILE_URL] = creator.profileUrl
        savedStateHandle[KEY_UPLOADS_QUERY] = creator.uploadsQuery
    }

    private fun restoredCreator(): CreatorProfile? {
        val source = savedStateHandle.get<String>(KEY_SOURCE)
            ?.let { name -> SourceKey.entries.firstOrNull { it.name == name } }
            ?: return null
        val displayName = savedStateHandle.get<String>(KEY_DISPLAY_NAME)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return CreatorProfile(
            source = source,
            displayName = displayName,
            profileId = savedStateHandle[KEY_PROFILE_ID],
            profileUrl = savedStateHandle[KEY_PROFILE_URL],
            uploadsQuery = savedStateHandle[KEY_UPLOADS_QUERY],
        )
    }

    companion object {
        fun factory(
            coordinator: CreatorProfileCoordinator,
            animatedDurationEnricher: AnimatedDurationEnricher,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CreatorProfileViewModel(
                    coordinator = coordinator,
                    savedStateHandle = createSavedStateHandle(),
                    animatedDurationEnricher = animatedDurationEnricher,
                )
            }
        }

        internal const val KEY_SOURCE = "creator_source"
        internal const val KEY_DISPLAY_NAME = "creator_display_name"
        internal const val KEY_PROFILE_ID = "creator_profile_id"
        internal const val KEY_PROFILE_URL = "creator_profile_url"
        internal const val KEY_UPLOADS_QUERY = "creator_uploads_query"
    }
}
