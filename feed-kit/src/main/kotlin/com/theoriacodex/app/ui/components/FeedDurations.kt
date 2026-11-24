package com.theoriacodex.app.ui.components

import com.theoriacodex.app.media.MediaDurationRouteViewModel
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.MediaDurationStates
import com.theoriacodex.app.media.noMediaDurationStateForPost
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.flow.Flow

data class FeedDurations(
    val states: MediaDurationStates = MediaDurationStates.EMPTY,
    val resolveInBackground: Boolean = false,
    val stateForPost: (Post) -> Flow<MediaDurationState?> = noMediaDurationStateForPost,
    val onFilterChanged: (Boolean) -> Unit = {},
    val onPostVisibilityChanged: (Post, Boolean) -> Unit = { _, _ -> },
    val onEnvironmentChanged: (lifecycleStarted: Boolean, scrollIdle: Boolean) -> Unit = { _, _ -> },
    val onAuthoritativeDurationKnown: (Post, Long) -> Unit = { _, _ -> },
) {
    companion object {
        val NONE = FeedDurations()
    }
}

fun MediaDurationRouteViewModel.feedDurations(states: MediaDurationStates, resolveInBackground: Boolean) = FeedDurations(
    states = states,
    resolveInBackground = resolveInBackground,
    stateForPost = ::observeState,
    onFilterChanged = ::onFilterChanged,
    onPostVisibilityChanged = ::onPostVisibilityChanged,
    onEnvironmentChanged = ::onEnvironmentChanged,
    onAuthoritativeDurationKnown = ::publishPlayerDuration,
)
