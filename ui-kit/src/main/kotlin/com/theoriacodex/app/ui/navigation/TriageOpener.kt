package com.theoriacodex.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

/**
 * The feed a triage deck works through: [source] with [feedKey], the same key the Viewer's launch context
 * carries (a search's query hash, or `codex:<id>` for a Codex or Likes).
 */
data class TriageRequest(val source: ViewerStreamSource, val feedKey: String)

/**
 * What a feed's grid shows at the moment Triage is tapped, handed to the deck as a grid hands its posts to the Viewer:
 * its visible [posts] in order, and how it picks the posts it shows from a list. [filters] is the grid's media, mark
 * and date filters, which a Viewer opened from the deck applies; [filterInRange] keeps the posts the grid would show
 * under a given date range, so a copy with another [filters] date range filters by that one.
 */
data class TriageSeed(
    val posts: List<Post>,
    val filters: SearchVisibilityFilters,
    val lengthFilterActive: Boolean,
    private val filterInRange: (posts: List<Post>, dateRange: DateRange?, knownDurationMsByPostId: Map<PostId, Long>) -> List<Post>,
) {
    /** Keeps the posts the grid would show given the lengths known so far, which it reads only while [lengthFilterActive]. */
    fun filter(posts: List<Post>, knownDurationMsByPostId: Map<PostId, Long>): List<Post> =
        filterInRange(posts, filters.dateRange, knownDurationMsByPostId)
}

/** Opens the triage deck from a feed's menu. Read from [LocalTriageOpener]. */
fun interface TriageOpener {
    fun openTriage(request: TriageRequest, seed: TriageSeed)

    companion object {
        val None = TriageOpener { _, _ -> }
    }
}

val LocalTriageOpener = staticCompositionLocalOf { TriageOpener.None }

/**
 * The action for a feed's filter sheet ([com.theoriacodex.app.ui.components.FeedFilterSheet]'s `onTriage`): opens
 * the deck over [feedKey] from [source], seeded with what the grid shows when it is tapped ([seed]). Null, so the
 * sheet offers no Triage, when there is no [feedKey] or the grid shows no posts, or when nothing in the app provides
 * a [LocalTriageOpener].
 */
@Composable
fun rememberTriageAction(source: ViewerStreamSource, feedKey: String?, seed: TriageSeed): (() -> Unit)? {
    val opener = LocalTriageOpener.current
    val latest = rememberUpdatedState(seed)
    val hasPosts = seed.posts.isNotEmpty()
    return remember(opener, source, feedKey, hasPosts) {
        if (opener === TriageOpener.None || feedKey.isNullOrBlank() || !hasPosts) null
        else ({ opener.openTriage(TriageRequest(source, feedKey), latest.value) })
    }
}
