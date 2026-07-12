package com.theoriacodex.app.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.ui.state.AppRouteSavedStateKeys
import com.theoriacodex.app.ui.state.RouteStateOwner
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerEffect
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerMediaState
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Resolves a post without exposing a provider, repository, or Android handle to Viewer state. */
internal fun interface ViewerPostResolver {
    suspend fun resolve(session: ViewerSessionIdentity, postId: PostId): Post?
}

/** Performs bounded media prefetch outside immutable state and platform playback ownership. */
internal fun interface ViewerMediaPrefetcher {
    suspend fun prefetch(session: ViewerSessionIdentity, media: ViewerMediaState): Boolean
}

/**
 * Route-scoped Viewer owner.
 *
 * Posts remain process-local. Saved state contains only the compact identity and current selection
 * needed to ask the shell/repository layer to reconstruct the session after process recreation.
 * ExoPlayer, animated decoders, launchers, and other Android handles remain in renderers/holders.
 */
internal class ViewerViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val postResolver: ViewerPostResolver? = null,
    private val mediaPrefetcher: ViewerMediaPrefetcher? = null,
    scopeOverride: CoroutineScope? = null,
) : ViewModel(), RouteStateOwner<ViewerUiState, ViewerAction, ViewerEffect> {
    private val lock = Any()
    private val workScope = scopeOverride ?: viewModelScope
    private val effectChannel = Channel<ViewerEffect>(capacity = Channel.BUFFERED)
    private val sessionJobs = mutableMapOf<ViewerWorkKey, Job>()
    private var restoredPageIndex = savedStateHandle[ViewerSavedStateKeys.PAGE_INDEX] ?: 0
    private var restoredMediaIndex = savedStateHandle[ViewerSavedStateKeys.MEDIA_INDEX] ?: 0

    private val mutableState = MutableStateFlow(restoredViewerState(savedStateHandle))
    override val state: StateFlow<ViewerUiState> = mutableState.asStateFlow()
    override val effects: Flow<ViewerEffect> = effectChannel.receiveAsFlow()
    private val mutableSession = MutableStateFlow<ViewerSession?>(null)
    val session: StateFlow<ViewerSession?> = mutableSession.asStateFlow()

    /** Compact identity awaiting a process-restoration payload, or null for a live/empty session. */
    val pendingRestoration: ViewerSessionIdentity?
        get() = state.value.takeIf { current -> current.pages.isEmpty() }?.session

    override fun onAction(action: ViewerAction) {
        val reduction = synchronized(lock) {
            if (action is ViewerAction.ReplaceSession) {
                cancelSessionJobs()
            }
            val restoresSavedSelection = action is ViewerAction.ReplaceSession &&
                mutableState.value.pages.isEmpty() &&
                mutableState.value.session == action.session
            val effectiveAction = restoredReplacement(action)
            var result = reduceViewerState(mutableState.value, effectiveAction)
            if (restoresSavedSelection && restoredMediaIndex > 0) {
                result = reduceViewerState(result.state, ViewerAction.SelectMedia(restoredMediaIndex))
            }
            result.also {
                mutableState.value = result.state
                persist(result.state)
            }
        }
        reduction.effects.forEach(::handleEffect)
    }

    /** Bridges the existing process-local shell handoff into the route contract. */
    fun replaceSession(session: ViewerSession) {
        mutableSession.value = session
        onAction(session.toReplaceViewerSessionAction())
    }

    /** Keeps transient launch metadata and immutable render pages under the same route owner. */
    fun updateSession(transform: (ViewerSession) -> ViewerSession) {
        val current = mutableSession.value ?: return
        val updated = transform(current)
        val currentIndex = state.value.currentPageIndex
        val currentMediaIndex = state.value.currentPage?.selectedMediaIndex ?: 0
        val replacement = updated.copy(
            context = updated.context.copy(startIndex = currentIndex),
        )
        replaceSession(replacement)
        if (currentMediaIndex > 0) onAction(ViewerAction.SelectMedia(currentMediaIndex))
    }

    fun applyResolvedPost(post: Post) {
        val current = mutableSession.value ?: return
        val index = current.posts.indexOfFirst { candidate -> candidate.id == post.id }
        if (index < 0) return
        mutableSession.value = current.copy(
            posts = current.posts.toMutableList().apply { this[index] = post },
        )
        state.value.session?.let { identity ->
            onAction(ViewerAction.ResolutionCompleted(identity, post))
        }
    }

    fun clearSession() {
        synchronized(lock) {
            cancelSessionJobs()
            mutableSession.value = null
            mutableState.value = ViewerUiState.Empty
            restoredPageIndex = 0
            restoredMediaIndex = 0
            clearSavedState()
        }
    }

    private fun restoredReplacement(action: ViewerAction): ViewerAction {
        if (action !is ViewerAction.ReplaceSession) return action
        val restoresSavedIdentity = mutableState.value.pages.isEmpty() &&
            mutableState.value.session == action.session
        if (!restoresSavedIdentity) return action
        return action.copy(initialPageIndex = restoredPageIndex)
    }

    private fun handleEffect(effect: ViewerEffect) {
        if (effect.session != state.value.session) return
        when (effect) {
            is ViewerEffect.ResolvePost -> {
                val resolver = postResolver
                if (resolver == null) {
                    effectChannel.trySend(effect)
                } else {
                    launchResolution(effect, resolver)
                }
            }

            is ViewerEffect.PrefetchMedia -> {
                val prefetcher = mediaPrefetcher
                if (prefetcher == null) {
                    effectChannel.trySend(effect)
                } else {
                    effect.mediaKeys.forEach { key -> launchPrefetch(effect.session, key, prefetcher) }
                }
            }

            else -> effectChannel.trySend(effect)
        }
    }

    private fun launchResolution(effect: ViewerEffect.ResolvePost, resolver: ViewerPostResolver) {
        launchSessionJob(ViewerWorkKey.Resolution(effect.postId)) {
            onAction(ViewerAction.ResolutionStarted(effect.session, effect.postId))
            try {
                val resolved = resolver.resolve(effect.session, effect.postId)
                coroutineContext.ensureActive()
                if (resolved == null) {
                    onAction(
                        ViewerAction.ResolutionFailed(
                            session = effect.session,
                            postId = effect.postId,
                            message = "Post was deleted",
                            recoverable = false,
                        )
                    )
                } else {
                    applyResolvedPost(resolved)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onAction(
                    ViewerAction.ResolutionFailed(
                        session = effect.session,
                        postId = effect.postId,
                        message = error.message ?: "Could not resolve this post",
                        recoverable = true,
                    )
                )
            }
        }
    }

    private fun launchPrefetch(
        session: ViewerSessionIdentity,
        mediaKey: ViewerMediaKey,
        prefetcher: ViewerMediaPrefetcher,
    ) {
        val media = state.value.pages
            .asSequence()
            .flatMap { page -> page.media.asSequence() }
            .firstOrNull { candidate -> candidate.key == mediaKey }
            ?: return
        launchSessionJob(ViewerWorkKey.Prefetch(mediaKey)) {
            onAction(ViewerAction.PrefetchStarted(session, mediaKey))
            val available = try {
                prefetcher.prefetch(session, media).also { coroutineContext.ensureActive() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                false
            }
            onAction(ViewerAction.PrefetchCompleted(session, mediaKey, available))
        }
    }

    private fun launchSessionJob(key: ViewerWorkKey, block: suspend CoroutineScope.() -> Unit) {
        synchronized(lock) {
            sessionJobs.remove(key)?.cancel()
            val job = workScope.launch(start = CoroutineStart.LAZY, block = block)
            sessionJobs[key] = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (sessionJobs[key] === job) sessionJobs.remove(key)
                }
            }
            job.start()
        }
    }

    private fun cancelSessionJobs() {
        sessionJobs.values.toList().forEach(Job::cancel)
        sessionJobs.clear()
    }

    private fun persist(current: ViewerUiState) {
        val session = current.session ?: run {
            clearSavedState()
            return
        }
        savedStateHandle[AppRouteSavedStateKeys.VIEWER_SESSION_ID] = session.value
        savedStateHandle[ViewerSavedStateKeys.QUERY_HASH] = session.queryHash
        savedStateHandle[ViewerSavedStateKeys.STREAM_KEY] = session.streamKey
        savedStateHandle[ViewerSavedStateKeys.PAGE_INDEX] = current.currentPageIndex
        savedStateHandle[ViewerSavedStateKeys.MEDIA_INDEX] = current.currentPage?.selectedMediaIndex ?: 0
        restoredPageIndex = current.currentPageIndex
        restoredMediaIndex = current.currentPage?.selectedMediaIndex ?: 0
    }

    private fun clearSavedState() {
        savedStateHandle.remove<String>(AppRouteSavedStateKeys.VIEWER_SESSION_ID)
        savedStateHandle.remove<String>(ViewerSavedStateKeys.QUERY_HASH)
        savedStateHandle.remove<String>(ViewerSavedStateKeys.STREAM_KEY)
        savedStateHandle.remove<Int>(ViewerSavedStateKeys.PAGE_INDEX)
        savedStateHandle.remove<Int>(ViewerSavedStateKeys.MEDIA_INDEX)
    }

    override fun onCleared() {
        synchronized(lock) {
            cancelSessionJobs()
            effectChannel.close()
        }
        super.onCleared()
    }

    companion object {
        fun factory(
            postResolver: ViewerPostResolver? = null,
            mediaPrefetcher: ViewerMediaPrefetcher? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ViewerViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    postResolver = postResolver,
                    mediaPrefetcher = mediaPrefetcher,
                )
            }
        }
    }

    private sealed interface ViewerWorkKey {
        data class Resolution(val postId: PostId) : ViewerWorkKey
        data class Prefetch(val mediaKey: ViewerMediaKey) : ViewerWorkKey
    }
}

internal object ViewerSavedStateKeys {
    const val QUERY_HASH = "viewer_query_hash"
    const val STREAM_KEY = "viewer_stream_key"
    const val PAGE_INDEX = "viewer_page_index"
    const val MEDIA_INDEX = "viewer_media_index"
}

internal fun ViewerSession.toViewerSessionIdentity(): ViewerSessionIdentity {
    return ViewerSessionIdentity(
        value = sessionId,
        queryHash = context.queryHash,
        streamKey = context.streamSource.name,
    )
}

internal fun ViewerSession.toReplaceViewerSessionAction(): ViewerAction.ReplaceSession {
    return ViewerAction.ReplaceSession(
        session = toViewerSessionIdentity(),
        posts = posts.toList(),
        initialPageIndex = context.startIndex,
        resolutionRequiredPostIds = posts
            .asSequence()
            .filter { post -> requiresViewerPostResolution(post, context.streamSource) }
            .mapTo(linkedSetOf(), Post::id),
    )
}

private fun restoredViewerState(savedStateHandle: SavedStateHandle): ViewerUiState {
    val sessionId = savedStateHandle.get<String>(AppRouteSavedStateKeys.VIEWER_SESSION_ID)
        ?.takeIf(String::isNotBlank)
        ?: return ViewerUiState.Empty
    return ViewerUiState(
        session = ViewerSessionIdentity(
            value = sessionId,
            queryHash = savedStateHandle[ViewerSavedStateKeys.QUERY_HASH],
            streamKey = savedStateHandle[ViewerSavedStateKeys.STREAM_KEY],
        ),
    )
}
