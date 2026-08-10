package com.theoriacodex.app.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

private val emptyMediaDurationState = flowOf<MediaDurationState?>(null)

internal val noMediaDurationStateForPost: (Post) -> Flow<MediaDurationState?> = {
    emptyMediaDurationState
}

/** Collects one post key so a badge update does not invalidate the route-wide feed. */
@Composable
internal fun observedMediaDurationMs(
    post: Post,
    stateForPost: (Post) -> Flow<MediaDurationState?>,
): Long? {
    val stateFlow = remember(post, stateForPost) { stateForPost(post) }
    val state by stateFlow.collectAsStateWithLifecycle(initialValue = null)
    return (state as? MediaDurationState.Known)?.durationMs
}
