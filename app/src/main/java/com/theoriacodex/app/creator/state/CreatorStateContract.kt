package com.theoriacodex.app.creator.state

import com.theoriacodex.app.creator.CreatorProfileCoordinator
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post

enum class CreatorEmptyReason {
    NO_CREATOR,
    UNSUPPORTED_SOURCE,
    NO_RESULTS,
}

enum class CreatorFailureReason {
    UNSUPPORTED_SOURCE,
    REQUEST_FAILED,
}

enum class CreatorRequestKind {
    REFRESH,
    PAGE,
}

data class CreatorRequestIdentity(
    val generation: Long,
    val kind: CreatorRequestKind,
    val queryHash: String,
) {
    init {
        require(generation > 0L) { "Creator request generation must be positive" }
        require(queryHash.isNotBlank()) { "Creator request query hash must not be blank" }
    }
}

data class CreatorUiState(
    val creator: CreatorProfile? = null,
    val queryHash: String? = null,
    val results: List<Post> = emptyList(),
    val isRefreshing: Boolean = false,
    val isPaging: Boolean = false,
    val canLoadMore: Boolean = false,
    val errorMessage: String? = null,
    val failureReason: CreatorFailureReason? = null,
    val emptyReason: CreatorEmptyReason? = CreatorEmptyReason.NO_CREATOR,
    val nextRequestGeneration: Long = 1L,
    val activeRequest: CreatorRequestIdentity? = null,
)

sealed interface CreatorAction {
    data class OpenCreator(val creator: CreatorProfile) : CreatorAction
    data object Refresh : CreatorAction
    data object LoadNextPage : CreatorAction
    data class OpenResult(val index: Int, val scrollOffsetHint: Int = 0) : CreatorAction
    data object Back : CreatorAction

    data class RefreshCompleted(
        val request: CreatorRequestIdentity,
        val snapshot: CreatorCoordinatorSnapshot,
    ) : CreatorAction

    data class RefreshFailed(
        val request: CreatorRequestIdentity,
        val message: String,
        val reason: CreatorFailureReason = CreatorFailureReason.REQUEST_FAILED,
    ) : CreatorAction

    data class PageLoaded(
        val request: CreatorRequestIdentity,
        val posts: List<Post>,
        val canLoadMore: Boolean,
    ) : CreatorAction

    data class PageFailed(
        val request: CreatorRequestIdentity,
        val message: String,
        val reason: CreatorFailureReason = CreatorFailureReason.REQUEST_FAILED,
    ) : CreatorAction

    data class RequestCancelled(val request: CreatorRequestIdentity) : CreatorAction
}

sealed interface CreatorEffect {
    val request: CreatorRequestIdentity?

    data class LoadCreator(
        override val request: CreatorRequestIdentity,
        val creator: CreatorProfile,
    ) : CreatorEffect

    data class Refresh(
        override val request: CreatorRequestIdentity,
    ) : CreatorEffect

    data class LoadNextPage(
        override val request: CreatorRequestIdentity,
    ) : CreatorEffect

    data class OpenViewer(
        val posts: List<Post>,
        val context: ViewerLaunchContext,
    ) : CreatorEffect {
        override val request: CreatorRequestIdentity? = null
    }

    data object NavigateBack : CreatorEffect {
        override val request: CreatorRequestIdentity? = null
    }
}

data class CreatorTransition(
    val state: CreatorUiState,
    val effect: CreatorEffect? = null,
)

data class CreatorCoordinatorSnapshot(
    val creator: CreatorProfile?,
    val queryHash: String?,
    val results: List<Post>,
    val loading: Boolean,
    val loadingMore: Boolean,
    val canLoadMore: Boolean,
    val errorMessage: String?,
    val failureReason: CreatorFailureReason? = null,
)

fun CreatorProfileCoordinator.toUiState(): CreatorUiState {
    return CreatorCoordinatorSnapshot(
        creator = activeCreator,
        queryHash = activeQueryHash,
        results = results,
        loading = loading,
        loadingMore = loadingMore,
        canLoadMore = canLoadMore,
        errorMessage = errorMessage,
        failureReason = legacyFailureReason(errorMessage),
    ).toUiState()
}

fun CreatorCoordinatorSnapshot.toUiState(): CreatorUiState {
    val copiedResults = results.toList()
    val copiedError = errorMessage?.takeIf(String::isNotBlank)
    val copiedFailureReason = failureReason ?: legacyFailureReason(copiedError)
    return CreatorUiState(
        creator = creator,
        queryHash = queryHash,
        results = copiedResults,
        isRefreshing = loading,
        isPaging = loadingMore,
        canLoadMore = canLoadMore,
        errorMessage = copiedError,
        failureReason = copiedFailureReason,
        emptyReason = inferCreatorEmptyReason(
            creator = creator,
            results = copiedResults,
            loading = loading,
            errorMessage = copiedError,
            failureReason = copiedFailureReason,
        ),
    )
}

fun CreatorUiState.reduce(action: CreatorAction): CreatorTransition {
    return when (action) {
        is CreatorAction.OpenCreator -> {
            val next = copy(
                creator = action.creator,
                queryHash = creatorQueryHash(action.creator),
                results = emptyList(),
                isRefreshing = true,
                isPaging = false,
                canLoadMore = false,
                errorMessage = null,
                failureReason = null,
                emptyReason = null,
            )
            next.beginRefresh { request -> CreatorEffect.LoadCreator(request, action.creator) }
        }

        CreatorAction.Refresh -> {
            if (creator == null || isRefreshing || isPaging) {
                unchanged()
            } else {
                beginRefresh { request -> CreatorEffect.Refresh(request) }
            }
        }

        CreatorAction.LoadNextPage -> {
            if (creator == null || isRefreshing || isPaging || !canLoadMore) {
                unchanged()
            } else {
                val request = nextRequest(CreatorRequestKind.PAGE)
                CreatorTransition(
                    state = copy(
                        isPaging = true,
                        errorMessage = null,
                        failureReason = null,
                        emptyReason = null,
                        nextRequestGeneration = request.generation + 1L,
                        activeRequest = request,
                    ),
                    effect = CreatorEffect.LoadNextPage(request),
                )
            }
        }

        is CreatorAction.OpenResult -> {
            if (creator == null || action.index !in results.indices) {
                unchanged()
            } else {
                CreatorTransition(
                    state = this,
                    effect = CreatorEffect.OpenViewer(
                        posts = results.toList(),
                        context = ViewerLaunchContext(
                            queryHash = queryHash ?: creatorQueryHash(creator),
                            startIndex = action.index,
                            streamSource = ViewerStreamSource.CREATOR_PROFILE,
                            scrollOffsetHint = action.scrollOffsetHint,
                        ),
                    ),
                )
            }
        }

        CreatorAction.Back -> CreatorTransition(this, CreatorEffect.NavigateBack)
        is CreatorAction.RefreshCompleted -> {
            if (!accepts(action.request, CreatorRequestKind.REFRESH)) {
                unchanged()
            } else {
                val completed = action.snapshot.copy(loading = false, loadingMore = false).toUiState()
                CreatorTransition(
                    completed.copy(
                        nextRequestGeneration = nextRequestGeneration,
                        activeRequest = null,
                    )
                )
            }
        }

        is CreatorAction.RefreshFailed -> {
            if (!accepts(action.request, CreatorRequestKind.REFRESH)) {
                unchanged()
            } else {
                CreatorTransition(failed(action.message, action.reason, clearResults = true))
            }
        }

        is CreatorAction.PageLoaded -> {
            if (!accepts(action.request, CreatorRequestKind.PAGE)) {
                unchanged()
            } else {
                val merged = mergePosts(results, action.posts)
                CreatorTransition(
                    copy(
                        results = merged,
                        isPaging = false,
                        canLoadMore = action.canLoadMore,
                        errorMessage = null,
                        failureReason = null,
                        emptyReason = if (merged.isEmpty()) CreatorEmptyReason.NO_RESULTS else null,
                        activeRequest = null,
                    )
                )
            }
        }

        is CreatorAction.PageFailed -> {
            if (!accepts(action.request, CreatorRequestKind.PAGE)) {
                unchanged()
            } else {
                CreatorTransition(failed(action.message, action.reason, clearResults = false))
            }
        }

        is CreatorAction.RequestCancelled -> {
            if (activeRequest != action.request) {
                unchanged()
            } else {
                CreatorTransition(
                    copy(
                        isRefreshing = false,
                        isPaging = false,
                        errorMessage = null,
                        failureReason = null,
                        emptyReason = when {
                            creator == null -> CreatorEmptyReason.NO_CREATOR
                            results.isEmpty() -> CreatorEmptyReason.NO_RESULTS
                            else -> null
                        },
                        activeRequest = null,
                    )
                )
            }
        }
    }
}

private fun CreatorUiState.beginRefresh(
    effect: (CreatorRequestIdentity) -> CreatorEffect,
): CreatorTransition {
    val request = nextRequest(CreatorRequestKind.REFRESH)
    return CreatorTransition(
        state = copy(
            isRefreshing = true,
            isPaging = false,
            canLoadMore = false,
            errorMessage = null,
            failureReason = null,
            emptyReason = null,
            nextRequestGeneration = request.generation + 1L,
            activeRequest = request,
        ),
        effect = effect(request),
    )
}

private fun CreatorUiState.nextRequest(kind: CreatorRequestKind): CreatorRequestIdentity {
    val requestQueryHash = queryHash ?: creator?.let(::creatorQueryHash)
        ?: error("A Creator request requires creator identity")
    return CreatorRequestIdentity(
        generation = nextRequestGeneration,
        kind = kind,
        queryHash = requestQueryHash,
    )
}

private fun CreatorUiState.accepts(
    request: CreatorRequestIdentity,
    kind: CreatorRequestKind,
): Boolean = request.kind == kind && activeRequest == request

private fun CreatorUiState.failed(
    message: String,
    reason: CreatorFailureReason,
    clearResults: Boolean,
): CreatorUiState {
    val remainingResults = if (clearResults) emptyList() else results
    return copy(
        results = remainingResults,
        isRefreshing = false,
        isPaging = false,
        canLoadMore = false,
        errorMessage = message,
        failureReason = reason,
        emptyReason = if (
            reason == CreatorFailureReason.UNSUPPORTED_SOURCE && remainingResults.isEmpty()
        ) {
            CreatorEmptyReason.UNSUPPORTED_SOURCE
        } else {
            null
        },
        activeRequest = null,
    )
}

private fun CreatorUiState.unchanged(): CreatorTransition = CreatorTransition(this)

private fun inferCreatorEmptyReason(
    creator: CreatorProfile?,
    results: List<Post>,
    loading: Boolean,
    errorMessage: String?,
    failureReason: CreatorFailureReason?,
): CreatorEmptyReason? {
    if (creator == null) return CreatorEmptyReason.NO_CREATOR
    if (loading || results.isNotEmpty()) return null
    if (failureReason == CreatorFailureReason.UNSUPPORTED_SOURCE) {
        return CreatorEmptyReason.UNSUPPORTED_SOURCE
    }
    if (errorMessage != null) return null
    return CreatorEmptyReason.NO_RESULTS
}

private fun legacyFailureReason(message: String?): CreatorFailureReason? {
    if (message == null) return null
    return if (message.startsWith("Creator browsing is not available")) {
        CreatorFailureReason.UNSUPPORTED_SOURCE
    } else {
        CreatorFailureReason.REQUEST_FAILED
    }
}

private fun creatorQueryHash(creator: CreatorProfile): String {
    val key = creator.uploadsQuery
        ?: creator.profileId
        ?: creator.profileUrl
        ?: creator.displayName
    return "creator:${creator.source.name}:$key"
}

private fun mergePosts(existing: List<Post>, incoming: List<Post>): List<Post> {
    if (incoming.isEmpty()) return existing
    val byId = LinkedHashMap(existing.associateBy(Post::id))
    incoming.forEach { post -> byId[post.id] = post }
    return byId.values.toList()
}
