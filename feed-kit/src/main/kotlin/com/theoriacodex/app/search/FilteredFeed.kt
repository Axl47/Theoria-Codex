package com.theoriacodex.app.search

import androidx.compose.runtime.Composable
import com.theoriacodex.app.media.DurationFilterMetadata
import com.theoriacodex.app.ui.components.FeedTraceSections
import com.theoriacodex.app.ui.components.ListFrame
import com.theoriacodex.app.ui.components.RetainedFrames
import com.theoriacodex.app.ui.components.appendedAfterRemovals
import com.theoriacodex.app.ui.components.appendedTo
import com.theoriacodex.app.ui.components.rememberComputedOffMain
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

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
        posts, *inputs, durationsKey, section = FeedTraceSections.FILTER, retainKey = FilteredFrames.at(retainKey),
        extend = { previous -> extendFiltered(previous, posts, known, pass) },
    ) { FilteredPosts(pass(posts), known) }.visible
}

internal class FilteredPosts(val visible: List<Post>, val durations: Map<PostId, Long>)

private val FilteredFrames = RetainedFrames<Post, FilteredPosts>()

internal fun extendFiltered(
    previous: ListFrame<Post, FilteredPosts>,
    posts: List<Post>,
    durations: Map<PostId, Long>,
    filter: (List<Post>) -> List<Post>,
): FilteredPosts? {
    val shown = previous.value.visible
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

