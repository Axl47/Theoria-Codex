package com.theoriacodex.app.search

import androidx.compose.runtime.Composable
import com.theoriacodex.app.media.DurationFilterMetadata
import com.theoriacodex.app.ui.components.FeedTraceSections
import com.theoriacodex.app.ui.components.ListFrame
import com.theoriacodex.app.ui.components.appendedAfterRemovals
import com.theoriacodex.app.ui.components.appendedTo
import com.theoriacodex.app.ui.components.rememberComputedOffMain
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

/**
 * The feed's [posts] that [filters] let through. A page appended to [posts] filters only that page; a filter, mark or
 * duration change refilters off the main thread while the previous list stays on screen. Over the length step's
 * presented list, a length that changes nothing shown costs no pass, and one that reveals posts filters only those.
 * [retainKey] names the feed, so an unchanged result is not filtered again when the feed returns from the Viewer.
 */
@Composable
fun rememberFilteredFeed(
    posts: List<Post>,
    filters: SearchVisibilityFilters,
    unknownDurationPolicy: UnknownAnimatedDurationPolicy,
    durations: DurationFilterMetadata,
    marks: PostMarkInputs = NO_MARKS,
    retainKey: String,
): List<Post> = rememberCarriedFilter(posts, durations, filters, marks, unknownDurationPolicy, retainKey = retainKey) { candidates, known ->
    filterSearchResults(
        candidates, filters, marks.liked, marks.saved, marks.watched, unknownDurationPolicy, known, marks.retained,
    )
}

/**
 * [filter] over [posts] with the known lengths of [durations], computed as [rememberFilteredFeed] describes: the result
 * is carried over to a longer or revealed list, and recomputed off the main thread when [inputs] (everything else
 * [filter] reads, compared by equality) or the lengths change. [filter] must keep or drop each post on its own.
 */
@Composable
fun rememberCarriedFilter(
    posts: List<Post>,
    durations: DurationFilterMetadata,
    vararg inputs: Any?,
    retainKey: String,
    filter: (candidates: List<Post>, known: Map<PostId, Long>) -> List<Post>,
): List<Post> {
    val known = durations.knownDurationMsByPostId
    val pass = { candidates: List<Post> -> filter(candidates, known) }
    val durationsKey = if (posts === durations.presentedPosts) durations.presentedDurationsKey else known
    return rememberComputedOffMain(
        posts, *inputs, durationsKey, section = FeedTraceSections.FILTER, retainKey = retainKey,
        extend = { previous -> extendFiltered(previous, posts, known, pass) },
    ) { FilteredPosts(pass(posts), known) }.visible
}

/** A visibility filter's result and the known lengths it read. */
internal class FilteredPosts(val visible: List<Post>, val durations: Map<PostId, Long>)

/**
 * [previous]'s result carried over to [posts], filtering only the appended posts, when [posts] is the previous list
 * with hidden posts removed and new posts appended and every post kept in place has the length [previous] read.
 * Null otherwise, so the whole list is filtered. The filter's other inputs must be the ones [previous] used.
 */
internal fun extendFiltered(
    previous: ListFrame<Post, FilteredPosts>,
    posts: List<Post>,
    durations: Map<PostId, Long>,
    filter: (List<Post>) -> List<Post>,
): FilteredPosts? {
    val shown = previous.value.visible
    // Same lengths: only a plain append can be carried, and checking it needs no length lookups.
    if (durations === previous.value.durations) {
        return posts.appendedTo(previous.list)?.let { FilteredPosts(shown + filter(it), durations) }
    }
    var nextShown = 0
    val appended = posts.appendedAfterRemovals(previous.list) { post, kept ->
        val wasShown = nextShown < shown.size && shown[nextShown] === post
        if (wasShown) nextShown++
        if (kept) durations[post.id] == previous.value.durations[post.id] else !wasShown
    } ?: return null
    return FilteredPosts(shown + filter(appended), durations)
}

private val NO_MARKS = PostMarkInputs(emptySet(), emptySet(), emptySet(), emptySet())
