package com.theoriacodex.app.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.DurationFilterReadiness
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.rememberDurationFilterMetadata
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

internal data class SearchDurationFilterPresentation(
    val active: Boolean,
    val unknownPolicy: UnknownAnimatedDurationPolicy,
    val knownDurationMsByPostId: Map<PostId, Long>,
    val stateByPostId: Map<PostId, MediaDurationState>,
    val readiness: DurationFilterReadiness,
)

@Composable
internal fun rememberSearchDurationFilterPresentation(
    posts: List<Post>,
    range: AnimatedDurationRange,
    durationStates: Map<MediaDurationKey, MediaDurationState>,
    resolveUnknownAnimatedDurations: Boolean,
): SearchDurationFilterPresentation {
    val active = !range.isFullRange
    val unknownPolicy = remember(resolveUnknownAnimatedDurations) {
        if (resolveUnknownAnimatedDurations) {
            UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND
        } else {
            UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS
        }
    }
    val metadata = rememberDurationFilterMetadata(posts, durationStates, active)
    val readiness = metadata.readiness
    return SearchDurationFilterPresentation(
        active = active,
        unknownPolicy = unknownPolicy,
        knownDurationMsByPostId = metadata.knownDurationMsByPostId,
        stateByPostId = metadata.stateByPostId,
        readiness = readiness,
    )
}
