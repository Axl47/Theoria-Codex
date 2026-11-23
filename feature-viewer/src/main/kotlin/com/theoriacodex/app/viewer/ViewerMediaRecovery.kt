package com.theoriacodex.app.viewer

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** What asking the source again for a post's failed media came to ([recoverMedia]). */
enum class ViewerMediaRecovery {
    /** New media replaced the failed media; for an episode, its player is replaced too. */
    REPLACED,

    /** No answer within the session's lookup limit, a failure, or the same links again: the media stays as it was. */
    NOTHING_NEW,
}

/** How a page's failed media asks the source again, through the Viewer's route ([ViewerScreenHost.onRequestMediaRecovery]). */
interface ViewerMediaRecoveryRequest {
    /** An image's ask: the route looks the post up in its own scope, and a recovered post replaces the image. */
    fun ask(post: Post, failedMedia: ImageRef)

    /**
     * An episode's ask for fresh links ([ImageRef.streams]), awaited by its player, which decides what to do next from
     * what it came to. It runs in the player's coroutine, so leaving the page cancels the lookup.
     */
    suspend fun recoverStreams(post: Post, failedMedia: ImageRef): ViewerMediaRecovery
}

/**
 * Asks [recover] for working media for [post], whose [failedMedia] failed, within the session's lookup limit
 * ([viewerResolutionTimeoutMs]), and applies what comes back. The result is dropped, before the source records it
 * ([recover]'s `stillApplies`), when the session changed or the page's post did (its lookup finished): applying it
 * would replace the newer post with the older one. Cancelling the caller cancels the lookup.
 */
suspend fun ViewerViewModel.recoverMedia(
    post: Post,
    failedMedia: ImageRef,
    recover: suspend (session: ViewerSession, stillApplies: () -> Boolean) -> Post?,
): ViewerMediaRecovery {
    val active = session.value ?: return ViewerMediaRecovery.NOTHING_NEW
    fun stillApplies() = session.value?.sessionId == active.sessionId && state.value.pages.any { page -> page.post == post }
    if (!stillApplies()) return ViewerMediaRecovery.NOTHING_NEW
    val recovered = try {
        withTimeoutOrNull(viewerResolutionTimeoutMs(state.value.series)) { recover(active, ::stillApplies) }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
    if (recovered == null || !recovered.refreshes(failedMedia) || !stillApplies()) return ViewerMediaRecovery.NOTHING_NEW
    applyResolvedPost(recovered, recovered = true)
    return ViewerMediaRecovery.REPLACED
}

/**
 * Whether this recovered post brings anything [failed] lacked. Media with streams (an episode) needs other links:
 * the same ones would rebuild the same failing player. Other media is taken as new, as its source answered.
 */
private fun Post.refreshes(failed: ImageRef): Boolean = failed.streams.isEmpty() || media.none { it.streams == failed.streams }
