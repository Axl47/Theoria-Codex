package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.domain.model.PostId

/** Capture the producing media identity so late renderer completions cannot advance a replacement. */
internal fun ViewerUiState.completionAction(
    postId: PostId,
    mediaIndex: Int,
    generation: Long,
    onAction: (ViewerAction) -> Unit,
): () -> Unit = {
    session?.let { onAction(ViewerAction.PlaybackCompleted(it, ViewerMediaKey(postId, mediaIndex), generation)) }
}
