package com.theoriacodex.app.creator

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.creator.state.CreatorAction
import com.theoriacodex.app.creator.state.CreatorEffect
import com.theoriacodex.app.creator.state.CreatorFailureReason
import com.theoriacodex.app.creator.state.CreatorRequestKind
import com.theoriacodex.app.creator.state.CreatorUiState
import com.theoriacodex.app.creator.state.reduce
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.RelatedPostsDiagnostics
import com.theoriacodex.app.related.UnsupportedRelatedPostsLoader
import com.theoriacodex.app.media.replacingPostSnapshot
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Navigation-scoped owner for Creator identity, requests, paging, and navigation effects. */
internal class CreatorProfileViewModel(
    private val engine: CreatorRouteEngine,
    private val savedStateHandle: SavedStateHandle,
    coroutineScope: CoroutineScope? = null,
    relatedPostsLoader: RelatedPostsLoading = UnsupportedRelatedPostsLoader,
) : ViewModel(), RouteStateOwner<CreatorUiState, CreatorAction, CreatorEffect> {
    constructor(
        coordinator: CreatorProfileCoordinator,
        savedStateHandle: SavedStateHandle,
        relatedPostsLoader: RelatedPostsLoading = UnsupportedRelatedPostsLoader,
    ) : this(
        engine = coordinator,
        savedStateHandle = savedStateHandle,
        relatedPostsLoader = relatedPostsLoader,
    )

    private val ownerScope = coroutineScope ?: viewModelScope
    private var requestJob: Job? = null

    private val mutableState = MutableStateFlow(CreatorUiState())
    override val state: StateFlow<CreatorUiState> = mutableState.asStateFlow()

    private val relatedDiagnostics = RelatedPostsDiagnostics("creator", this)
    private val relatedPosts = RelatedPostsRouteController(
        scope = ownerScope,
        loader = relatedPostsLoader,
        currentState = { mutableState.value.relatedPosts },
        canonicalPosts = { mutableState.value.results },
        promotePost = { post, index ->
            val current = mutableState.value
            mutableState.value = current.copy(
                results = current.results.toMutableList().apply { add(index.coerceIn(0, size), post) },
            )
        },
        updateState = { related ->
            relatedDiagnostics.record("state updated", related)
            mutableState.value = mutableState.value.copy(relatedPosts = related)
        },
        onClear = { state, reason -> relatedDiagnostics.record("clear: $reason", state) },
    )

    private val effectChannel = Channel<CreatorEffect>(capacity = Channel.BUFFERED)
    override val effects: Flow<CreatorEffect> = effectChannel.receiveAsFlow()
    init {
        relatedDiagnostics.record("owner created", mutableState.value.relatedPosts)
        addCloseable("related-posts-diagnostics", java.io.Closeable {
            relatedDiagnostics.record("owner cleared", mutableState.value.relatedPosts)
        })
        restoredCreator()?.let { creator -> onAction(CreatorAction.OpenCreator(creator)) }
    }

    override fun onAction(action: CreatorAction) {
        when (action) {
            is CreatorAction.RelatedLikeCommitted -> {
                relatedPosts.onLikeCommitted(action.post, action.outcome)
                return
            }
            CreatorAction.DismissRelatedPosts -> {
                relatedPosts.clear()
                return
            }
            CreatorAction.RetryRelatedPosts -> {
                relatedPosts.retry()
                return
            }
            CreatorAction.ShowPreviousRelatedPosts -> {
                relatedPosts.showPreviousPage()
                return
            }
            CreatorAction.ShowNextRelatedPosts -> {
                relatedPosts.showNextPage()
                return
            }
            else -> Unit
        }
        val transition = mutableState.value.reduce(action)
        if (action == CreatorAction.SourceUnavailable ||
            (transition.effect as? CreatorEffect.LoadPage)?.request?.kind == CreatorRequestKind.REFRESH
        ) {
            relatedPosts.clear("creator refresh or capability changed")
        }
        mutableState.value = transition.state.copy(relatedPosts = mutableState.value.relatedPosts)
        transition.state.creator?.let(::persistCreator)
        transition.effect?.let(::handleEffect)
    }

    /** Only loss of this creator's capability invalidates its active request. */
    fun onSourceAvailabilityChanged() {
        val current = mutableState.value
        val creator = current.creator ?: return
        if (!engine.supports(creator)) {
            onAction(CreatorAction.SourceUnavailable)
            requestJob?.cancel(CancellationException("Creator capability unavailable"))
        } else if (current.failureReason == CreatorFailureReason.UNSUPPORTED_SOURCE) {
            onAction(CreatorAction.Refresh)
        }
    }

    suspend fun resolvePost(postId: PostId): Post? =
        engine.resolvePost(postId)?.also(::rememberResolvedPost)

    fun rememberResolvedPost(post: Post) {
        val current = mutableState.value
        mutableState.value = current.copy(results = current.results.replacingPostSnapshot(post))
    }

    private fun handleEffect(effect: CreatorEffect) {
        when (effect) {
            is CreatorEffect.LoadPage -> launchRequest(effect)
            is CreatorEffect.OpenViewer, CreatorEffect.NavigateBack -> effectChannel.trySend(effect)
        }
    }

    private fun launchRequest(effect: CreatorEffect.LoadPage) {
        requestJob?.cancel(CancellationException("Creator request replaced"))
        requestJob = ownerScope.launch {
            try {
                val page = engine.load(effect.creator, effect.pageToken)
                ensureActive()
                if (mutableState.value.activeRequest != effect.request) return@launch
                onAction(CreatorAction.PageLoaded(effect.request, page))
                if (effect.request.kind == CreatorRequestKind.REFRESH) {
                    // Accepted bookkeeping outlives the completed request, but not the route owner.
                    val posts = mutableState.value.results
                    ownerScope.launch {
                        runCatchingPreservingCancellation { engine.recordAcceptedVisit(effect.creator, posts) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onAction(CreatorAction.RequestFailed(
                    effect.request,
                    error.message ?: "Could not load creator uploads",
                    if (engine.supports(effect.creator)) CreatorFailureReason.REQUEST_FAILED
                    else CreatorFailureReason.UNSUPPORTED_SOURCE,
                ))
            } finally {
                if (!isActive) onAction(CreatorAction.RequestCancelled(effect.request))
            }
        }
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
            relatedPostsLoader: RelatedPostsLoading = UnsupportedRelatedPostsLoader,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CreatorProfileViewModel(
                    coordinator = coordinator,
                    savedStateHandle = createSavedStateHandle(),
                    relatedPostsLoader = relatedPostsLoader,
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
