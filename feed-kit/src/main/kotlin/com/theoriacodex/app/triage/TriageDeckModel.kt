package com.theoriacodex.app.triage

import com.theoriacodex.app.codex.SaveSuggestion
import com.theoriacodex.app.ui.components.FeedView
import com.theoriacodex.app.ui.navigation.TriageSeed
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The posts a deck works through so far, and whether its origin feed has more. [loaded] counts the posts the origin
 * feed has loaded before the grid's filters, so it grows with every page, even one none of whose posts pass them.
 */
data class TriageFeedSnapshot(val posts: List<Post>, val canLoadMore: Boolean, val loaded: Int = posts.size)

/** Where a deck's posts come from: a live feed that pages, or a fixed list. */
interface TriageFeed {
    /** The feed's posts, emitted again each time it grows. */
    val snapshots: Flow<TriageFeedSnapshot>

    /** Asks the feed for its next page; the result arrives through [snapshots]. */
    fun loadNextPage()

    companion object {
        /** A list that never grows, such as a Codex. */
        fun fixed(posts: List<Post>): TriageFeed = object : TriageFeed {
            override val snapshots = flowOf(TriageFeedSnapshot(posts, canLoadMore = false))
            override fun loadNextPage() = Unit
        }
    }
}

/** What Triage, tapped now, takes over from this grid: what it shows and how it filters. */
fun FeedView.triageSeed(): TriageSeed = TriageSeed(visiblePosts, filters, durationFilterActive) { posts, dateRange, lengths ->
    (if (dateRange == filters.dateRange) postFilter else postFilter.withDateRange(dateRange)).filter(posts, lengths)
}

/**
 * This seed once Search's pending filters have applied: it filters, and its Viewer filters, by the settled query's
 * [dateRange]; when the query changed ([sameQuery] false) it takes none of the old grid's posts.
 */
fun TriageSeed.settled(sameQuery: Boolean, dateRange: DateRange?): TriageSeed =
    copy(posts = if (sameQuery) posts else emptyList(), filters = filters.copy(dateRange = dateRange))

/** A single-tag entry of the active profile's For You hide list. */
data class HiddenTag(val source: SourceKey, val tag: String)

/** Reverses exactly what one committed write did, and returns once it has. */
typealias TriageReversal = suspend () -> Unit

/**
 * What the deck does to the world beyond saving, which [com.theoriacodex.app.codex.QuickSave] covers. Each write
 * returns once it has committed and throws when it fails; none of them toggles.
 */
interface TriageActions {
    /** The active profile's single-tag For You hide entries, as they change. */
    val hiddenTags: Flow<Set<HiddenTag>>

    /**
     * Likes [post] unless it, or another site's copy of its file, is already liked. Returns how to remove exactly the
     * Like it added, or null when it added none.
     */
    suspend fun like(post: Post): TriageReversal?

    /** Adds [tag] to the active profile's hide list; returns how to remove exactly that entry, or null when it was there. */
    suspend fun hide(source: SourceKey, tag: String): TriageReversal?
}

/**
 * Takes posts out of the Codex a deck was opened on. A deck given one removes on a left swipe instead of skipping.
 */
fun interface TriageRemoval {
    /** Removes [post]; returns how to put it back exactly, or null when it was not there. Throws when it fails. */
    suspend fun remove(post: Post): TriageReversal?
}

/** What a swipe asked for. */
sealed interface TriageChoice {
    data object Like : TriageChoice
    data object Skip : TriageChoice
    data object Remove : TriageChoice
    data class Save(val codexId: String, val codexName: String) : TriageChoice
    data class Hide(val tag: String) : TriageChoice
}

/** The deck's latest decision, kept so Undo can reverse it, even while its write is still running. */
sealed interface TriageDecision {
    val post: Post

    data class Liked(override val post: Post) : TriageDecision
    data class Skipped(override val post: Post) : TriageDecision
    data class Removed(override val post: Post) : TriageDecision
    data class Saved(override val post: Post, val codexName: String) : TriageDecision
    data class Hid(override val post: Post, val tag: String) : TriageDecision
}

/** What the deck says once a decision's write is known: it is done, or it [failed] and its card came back. */
data class TriageNotice(val decision: TriageDecision, val failed: Boolean)

/**
 * The deck's position over the posts it has been given, its last decision, and the tags hidden in it. Cards that
 * carry a [passedOverTags] tag are passed over, so [current] and [next] are never one of them.
 */
data class TriageDeckState(
    val posts: List<Post> = emptyList(),
    val index: Int = 0,
    val canLoadMore: Boolean = false,
    val loaded: Boolean = false,
    /** The decision Undo reverses; null when there is nothing to undo. */
    val last: TriageDecision? = null,
    val notice: TriageNotice? = null,
    /** Tags hidden from this deck, as [normalizeMatchToken] spells them. */
    val passedOverTags: Set<String> = emptySet(),
    /** Posts a left swipe took out of the deck's Codex, until Undo or a failed write puts them back. */
    val removed: Set<PostId> = emptySet(),
) {
    val current: Post? get() = posts.getOrNull(index)
    val next: Post? get() = posts.getOrNull(shownFrom(index + 1))

    /** Every post is decided and the origin feed has no more. */
    val finished: Boolean get() = loaded && index >= posts.size && !canLoadMore

    /**
     * The posts a tap on the current card opens the Viewer on, less those taken out of the deck's Codex, and the
     * current card's place among them.
     */
    fun viewerLaunch(): Pair<List<Post>, Int> {
        val kept = posts.filterNot { it.id in removed }
        return kept to kept.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
    }

    /** The first card at or after [from] that is not passed over; the end of [posts] when there is none. */
    fun shownFrom(from: Int): Int {
        var at = from
        while (at < posts.size && posts[at].carriesAnyTag(passedOverTags)) at++
        return at
    }
}

/**
 * What a committed swipe in [direction] asks for, given the Codex it would save to, the tag it would hide, and
 * whether left [removes] from the deck's Codex rather than skipping.
 * Null when it asks for a save and there is no Codex to save to (none suggested, as when the post is already in
 * every Codex), which the screen answers by opening the picker.
 * A downward swipe on a post with nothing to hide is a skip.
 */
internal fun choiceFor(
    direction: SwipeDirection,
    saveTarget: SaveSuggestion?,
    hideTag: String?,
    removes: Boolean = false,
): TriageChoice? = when (direction) {
    SwipeDirection.Right -> TriageChoice.Like
    SwipeDirection.Left -> if (removes) TriageChoice.Remove else TriageChoice.Skip
    SwipeDirection.Up -> saveTarget?.let { TriageChoice.Save(it.codexId, it.name) }
    SwipeDirection.Down -> hideTag?.let(TriageChoice::Hide) ?: TriageChoice.Skip
}
