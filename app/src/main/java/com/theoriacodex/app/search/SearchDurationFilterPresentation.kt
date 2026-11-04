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
    val presentedPosts: List<Post>?,
    val blocksPaging: Boolean,
)

@Composable
internal fun rememberSearchDurationFilterPresentation(
    posts: List<Post>,
    range: AnimatedDurationRange,
    durationStates: Map<MediaDurationKey, MediaDurationState>,
    resolveUnknownAnimatedDurations: Boolean,
    retainKey: String,
): SearchDurationFilterPresentation {
    val active = !range.isFullRange
    val unknownPolicy = unknownAnimatedDurationPolicy(resolveUnknownAnimatedDurations)
    val metadata = rememberDurationFilterMetadata(posts, durationStates, active, retainKey)
    val readiness = metadata.readiness
    return SearchDurationFilterPresentation(
        active = active,
        unknownPolicy = unknownPolicy,
        knownDurationMsByPostId = metadata.knownDurationMsByPostId,
        stateByPostId = metadata.stateByPostId,
        readiness = readiness,
        presentedPosts = metadata.presentedPosts,
        blocksPaging = metadata.blocksPaging,
    )
}
