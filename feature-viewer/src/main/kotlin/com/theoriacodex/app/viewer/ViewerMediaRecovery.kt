package com.theoriacodex.app.viewer

import com.theoriacodex.domain.coroutines.runCatchingExceptionsPreservingCancellation
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.withTimeoutOrNull

enum class ViewerMediaRecovery {
    REPLACED,

    NOTHING_NEW,
}

interface ViewerMediaRecoveryRequest {
    fun ask(post: Post, failedMedia: ImageRef)

    suspend fun recoverStreams(post: Post, failedMedia: ImageRef): ViewerMediaRecovery
}

suspend fun ViewerViewModel.recoverMedia(
    post: Post,
    failedMedia: ImageRef,
    recover: suspend (session: ViewerSession, stillApplies: () -> Boolean) -> Post?,
): ViewerMediaRecovery {
    val active = session.value ?: return ViewerMediaRecovery.NOTHING_NEW
    fun stillApplies() = session.value?.sessionId == active.sessionId && state.value.pages.any { page -> page.post == post }
    if (!stillApplies()) return ViewerMediaRecovery.NOTHING_NEW
    val recovered = runCatchingExceptionsPreservingCancellation {
        withTimeoutOrNull(viewerResolutionTimeoutMs(state.value.series)) { recover(active, ::stillApplies) }
    }.getOrNull()
    if (recovered == null || !recovered.refreshes(failedMedia) || !stillApplies()) return ViewerMediaRecovery.NOTHING_NEW
    applyResolvedPost(recovered, recovered = true)
    return ViewerMediaRecovery.REPLACED
}

private fun Post.refreshes(failed: ImageRef): Boolean = failed.streams.isEmpty() || media.none { it.streams == failed.streams }
