package com.theoriacodex.app.triage

import com.theoriacodex.app.codex.ChosenCodex
import com.theoriacodex.app.codex.SaveSuggestion
import com.theoriacodex.app.ui.components.FeedView
import com.theoriacodex.app.ui.navigation.TriageSeed
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.vision.PictureTags
import com.theoriacodex.domain.tags.normalizeMatchToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class TriageFeedSnapshot(
    val posts: List<Post>,
    val canLoadMore: Boolean,
    val loaded: Int = posts.size,
    val loadingMore: Boolean = false,
    val pageFailed: Boolean = false,
)

interface TriageFeed {
    val snapshots: Flow<TriageFeedSnapshot>

    fun loadNextPage()

    fun retryPage()

    companion object {
        fun fixed(posts: List<Post>): TriageFeed = object : TriageFeed {
            override val snapshots = flowOf(TriageFeedSnapshot(posts, canLoadMore = false))
            override fun loadNextPage() = Unit
            override fun retryPage() = Unit
        }
    }
}

fun FeedView.triageSeed(): TriageSeed = TriageSeed(visiblePosts, filters, durationFilterActive) { posts, dateRange, lengths ->
    (if (dateRange == filters.dateRange) postFilter else postFilter.withDateRange(dateRange)).filter(posts, lengths)
}

fun TriageSeed.settled(sameQuery: Boolean, dateRange: DateRange.Between?): TriageSeed =
    copy(posts = if (sameQuery) posts else emptyList(), filters = filters.copy(dateRange = dateRange))

data class HiddenTag(val source: SourceKey, val tag: String)

typealias TriageReversal = suspend () -> Unit

interface TriageActions {
    val hiddenTags: Flow<Set<HiddenTag>>

    suspend fun like(post: Post): TriageReversal?

    suspend fun hide(source: SourceKey, tag: String): TriageReversal?

    fun atDecision(): TriageActions = this
}

fun interface TriageRemoval {
    suspend fun remove(post: Post): TriageReversal?
}

sealed interface TriageChoice {
    data object Like : TriageChoice
    data object Skip : TriageChoice

    data object Keep : TriageChoice
    data object Remove : TriageChoice
    data class Save(val codex: ChosenCodex) : TriageChoice
    data class Hide(val tag: String) : TriageChoice
}

sealed interface TriageDecision {
    val post: Post

    data class Liked(override val post: Post) : TriageDecision
    data class Skipped(override val post: Post) : TriageDecision
    data class Kept(override val post: Post) : TriageDecision
    data class Removed(override val post: Post) : TriageDecision
    data class Saved(override val post: Post, val codexName: String) : TriageDecision
    data class Hid(override val post: Post, val tag: String) : TriageDecision
}

data class TriageNotice(val decision: TriageDecision, val failed: Boolean)

data class TriageDeckState(
    val posts: List<Post> = emptyList(),
    val index: Int = 0,
    val canLoadMore: Boolean = false,
    val loaded: Boolean = false,
    val pageFailed: Boolean = false,
    val last: TriageDecision? = null,
    val notice: TriageNotice? = null,
    val passedOverTags: Map<SourceKey, Set<String>> = emptyMap(),
    val ownerHiddenTags: Map<SourceKey, Set<String>> = emptyMap(),
    val pictures: PictureTags = PictureTags.None,
    val restored: PostId? = null,
    val removed: Set<PostId> = emptySet(),
    val elsewhere: Set<PostId> = emptySet(),
) {
    val current: Post? get() = posts.getOrNull(index)
    val next: Post? get() = posts.getOrNull(shownFrom(index + 1))

    val finished: Boolean get() = loaded && index >= posts.size && !canLoadMore && !pageFailed

    val needsRetry: Boolean get() = loaded && index >= posts.size && pageFailed

    fun viewerLaunch(): Pair<List<Post>, Int> {
        val kept = posts.filterNot { it.id in removed }
        return kept to kept.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
    }

    fun shownFrom(from: Int): Int {
        var at = from
        while (at < posts.size && passesOver(posts[at])) at++
        return at
    }

    private fun passesOver(post: Post): Boolean {
        if (post.id == restored) return false
        return post.id in elsewhere || post.hiddenBy(passedOverTags, pictures) || post.hiddenBy(ownerHiddenTags, pictures)
    }
}

internal fun choiceFor(
    direction: SwipeDirection,
    saveTarget: SaveSuggestion?,
    hideTag: String?,
    removes: Boolean = false,
): TriageChoice? = when (direction) {
    SwipeDirection.Right -> if (removes) TriageChoice.Keep else TriageChoice.Like
    SwipeDirection.Left -> if (removes) TriageChoice.Remove else TriageChoice.Skip
    SwipeDirection.Up -> saveTarget?.let { TriageChoice.Save(ChosenCodex.Existing(it.codexId, it.name)) }
    SwipeDirection.Down -> hideTag?.let(TriageChoice::Hide) ?: TriageChoice.Skip
}
