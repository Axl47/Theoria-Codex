package com.theoriacodex.app.search

import androidx.compose.runtime.Composable
import com.theoriacodex.app.ui.components.FeedTraceSections
import com.theoriacodex.app.ui.components.rememberComputedOffMain
import com.theoriacodex.app.viewer.traceMediaSection
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

/**
 * The feed's [posts] that [filters] let through. A page appended to [posts] filters only that page; a filter, mark or
 * duration change refilters off the main thread while the previous list stays on screen.
 */
@Composable
internal fun rememberFilteredFeed(
    posts: List<Post>,
    filters: SearchVisibilityFilters,
    unknownDurationPolicy: UnknownAnimatedDurationPolicy,
    knownDurations: Map<PostId, Long>,
    marks: PostMarkInputs = NO_MARKS,
): List<Post> {
    val filter = { candidates: List<Post> ->
        traceMediaSection(FeedTraceSections.FILTER) {
            filterSearchResults(
                candidates, filters, marks.liked, marks.saved, marks.watched, unknownDurationPolicy, knownDurations,
                marks.retained,
            )
        }
    }
    return rememberComputedOffMain(
        posts, filters, marks, unknownDurationPolicy, knownDurations,
        extend = { previous, appended -> previous + filter(appended) },
    ) { filter(posts) }
}

private val NO_MARKS = PostMarkInputs(emptySet(), emptySet(), emptySet(), emptySet())
