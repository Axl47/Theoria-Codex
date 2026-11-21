package com.theoriacodex.app.triage

import com.theoriacodex.app.codex.QuickSaveRecord
import com.theoriacodex.app.codex.SaveSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** The posts a deck works through so far, and whether its origin feed has more. */
data class TriageFeedSnapshot(val posts: List<Post>, val canLoadMore: Boolean)

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

/** A single-tag entry of the active profile's For You hide list. */
data class HiddenTag(val source: SourceKey, val tag: String)

/** What the deck does to the world beyond saving, which [com.theoriacodex.app.codex.QuickSave] covers. */
interface TriageActions {
    /** The active profile's single-tag For You hide entries, as they change. */
    val hiddenTags: Flow<Set<HiddenTag>>

    /** Likes [post] unless it is already liked; true when this call liked it. */
    suspend fun like(post: Post): Boolean

    /** Removes the Like [like] added. */
    fun unlike(post: Post)

    /** Adds [tag] to the active profile's hide list; false when it could not be added. */
    suspend fun hide(source: SourceKey, tag: String): Boolean

    /** Removes [tag] from the active profile's hide list. */
    suspend fun unhide(source: SourceKey, tag: String)
}

/** What a swipe asked for. */
sealed interface TriageChoice {
    data object Like : TriageChoice
    data object Skip : TriageChoice
    data class Save(val codexId: String, val codexName: String) : TriageChoice
    data class Hide(val tag: String) : TriageChoice
}

/**
 * The deck's latest decision, kept so Undo can reverse it. A like, save or hide settles a moment after the swipe;
 * until then [undoable] is false.
 */
sealed interface TriageDecision {
    val post: Post
    val undoable: Boolean

    /** [newLike] is null while the Like is still being written, false when the post was already liked. */
    data class Liked(override val post: Post, val newLike: Boolean? = null) : TriageDecision {
        override val undoable get() = newLike != null
    }

    data class Skipped(override val post: Post) : TriageDecision {
        override val undoable get() = true
    }

    /** [record] is null while the save is still running. */
    data class Saved(override val post: Post, val codexName: String, val record: QuickSaveRecord? = null) : TriageDecision {
        override val undoable get() = record != null
    }

    /** [added] is null while the hide is still running, false when the tag could not be added. */
    data class Hid(override val post: Post, val tag: String, val added: Boolean? = null) : TriageDecision {
        override val undoable get() = added != null
    }
}

/** The deck's position over the posts it has been given, and its last decision. */
data class TriageDeckState(
    val posts: List<Post> = emptyList(),
    val index: Int = 0,
    val canLoadMore: Boolean = false,
    val loaded: Boolean = false,
    val last: TriageDecision? = null,
) {
    val current: Post? get() = posts.getOrNull(index)
    val next: Post? get() = posts.getOrNull(index + 1)

    /** Every post is decided and the origin feed has no more. */
    val finished: Boolean get() = loaded && index >= posts.size && !canLoadMore
}

/**
 * What a committed swipe in [direction] asks for, given the Codex it would save to and the tag it would hide.
 * Null when it asks for a save and there is no Codex to save to, which the screen answers by opening the picker.
 * A downward swipe on a post with nothing to hide is a skip.
 */
internal fun choiceFor(direction: SwipeDirection, saveTarget: SaveSuggestion?, hideTag: String?): TriageChoice? = when (direction) {
    SwipeDirection.Right -> TriageChoice.Like
    SwipeDirection.Left -> TriageChoice.Skip
    SwipeDirection.Up -> saveTarget?.let { TriageChoice.Save(it.codexId, it.name) }
    SwipeDirection.Down -> hideTag?.let(TriageChoice::Hide) ?: TriageChoice.Skip
}
