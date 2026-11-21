package com.theoriacodex.app.ui.components

import com.theoriacodex.app.media.MediaDurationRouteViewModel
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.MediaDurationStates
import com.theoriacodex.app.media.noMediaDurationStateForPost
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.flow.Flow

/**
 * What a feed screen reads and reports about media lengths: the lengths its route knows, whether unknown lengths
 * resolve in the background, and where its cards and grid send what they learn. [NONE] is a feed without a duration
 * owner (previews and tests). Routes build one with [MediaDurationRouteViewModel.feedDurations].
 */
data class FeedDurations(
    val states: MediaDurationStates = MediaDurationStates.EMPTY,
    val resolveInBackground: Boolean = false,
    val stateForPost: (Post) -> Flow<MediaDurationState?> = noMediaDurationStateForPost,
    /** Whether a length filter is on, which makes the route resolve every length rather than only visible ones. */
    val onFilterChanged: (Boolean) -> Unit = {},
    val onPostVisibilityChanged: (Post, Boolean) -> Unit = { _, _ -> },
    /** The screen is started and its grid is not scrolling, so background resolution may run. */
    val onEnvironmentChanged: (lifecycleStarted: Boolean, scrollIdle: Boolean) -> Unit = { _, _ -> },
    val onAuthoritativeDurationKnown: (Post, Long) -> Unit = { _, _ -> },
) {
    companion object {
        val NONE = FeedDurations()
    }
}

/** This route owner's [states] and reporting, for its feed screen. Equal for the same owner, states and policy. */
fun MediaDurationRouteViewModel.feedDurations(states: MediaDurationStates, resolveInBackground: Boolean) = FeedDurations(
    states = states,
    resolveInBackground = resolveInBackground,
    stateForPost = ::observeState,
    onFilterChanged = ::onFilterChanged,
    onPostVisibilityChanged = ::onPostVisibilityChanged,
    onEnvironmentChanged = ::onEnvironmentChanged,
    onAuthoritativeDurationKnown = ::publishPlayerDuration,
)
