package com.theoriacodex.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

/**
 * The feed a triage deck works through: [source] with [feedKey], the same key the Viewer's launch context
 * carries (a search's query hash, or `codex:<id>` for a Codex or Likes), and what a left swipe does there.
 */
data class TriageRequest(
    val source: ViewerStreamSource,
    val feedKey: String,
    val mode: TriageMode = TriageMode.Browse,
) {
    /** The Codex a [ViewerStreamSource.CODEX] request names, from its `codex:<id>` feed key. */
    val codexId: String?
        get() = feedKey.takeIf { source == ViewerStreamSource.CODEX && it.startsWith(CODEX_FEED_PREFIX) }
            ?.removePrefix(CODEX_FEED_PREFIX)

    companion object {
        /** The deck over Codex [codexId]'s posts. */
        fun codex(codexId: String, mode: TriageMode = TriageMode.Browse) =
            TriageRequest(ViewerStreamSource.CODEX, CODEX_FEED_PREFIX + codexId, mode)
    }
}

/** What a left swipe in the deck does. */
sealed interface TriageMode {
    /** Left passes over the card. */
    data object Browse : TriageMode

    /**
     * Left takes the card out of the Codex the request names. With [alsoIn], the deck holds only that Codex's posts
     * that one of those Codices also holds: what cleaning the Codex against them would remove.
     */
    data class RemoveFromCodex(val alsoIn: Set<String> = emptySet()) : TriageMode
}

/**
 * What a feed's grid shows at the moment Triage is tapped, handed to the deck as a grid hands its posts to the Viewer:
 * its visible [posts] in order, and how it picks the posts it shows from a list. [filter] keeps those posts given the
 * lengths known so far; it reads lengths only while [lengthFilterActive]. [filters] is the grid's media and mark
 * filters, which a Viewer opened from the deck applies.
 */
data class TriageSeed(
    val posts: List<Post>,
    val filters: SearchVisibilityFilters,
    val lengthFilterActive: Boolean,
    val filter: (posts: List<Post>, knownDurationMsByPostId: Map<PostId, Long>) -> List<Post>,
) {
    companion object {
        /** The seed for a deck opened from no grid, whose feed finds its own posts, as cleaning's Review does. */
        val Unfiltered = TriageSeed(emptyList(), SearchVisibilityFilters(), lengthFilterActive = false) { posts, _ -> posts }
    }
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
 * the deck over [feedKey] from [source] in [mode], seeded with what the grid shows when it is tapped ([seed]). Null,
 * so the sheet offers no Triage, when there is no [feedKey] or the grid shows no posts, or when nothing in the app
 * provides a [LocalTriageOpener].
 */
@Composable
fun rememberTriageAction(
    source: ViewerStreamSource,
    feedKey: String?,
    seed: TriageSeed,
    mode: TriageMode = TriageMode.Browse,
): (() -> Unit)? {
    val opener = LocalTriageOpener.current
    val latest = rememberUpdatedState(seed)
    val hasPosts = seed.posts.isNotEmpty()
    return remember(opener, source, feedKey, hasPosts, mode) {
        if (opener === TriageOpener.None || feedKey.isNullOrBlank() || !hasPosts) null
        else ({ opener.openTriage(TriageRequest(source, feedKey, mode), latest.value) })
    }
}

private const val CODEX_FEED_PREFIX = "codex:"
