package com.theoriacodex.app.media

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

const val DEFAULT_DURATION_REVEAL_WINDOW = 8

data class LateDurationArrival(
    val postId: PostId,
    val beforeIndex: Int,
)

data class DurationReveal(
    val frontier: Int = 0,
    val frontierAnchor: PostId? = null,
    val awaiting: Set<PostId> = emptySet(),
    val lateArrivals: List<LateDurationArrival> = emptyList(),
) {
    fun present(posts: List<Post>): List<Post> {
        if (lateArrivals.isEmpty()) return posts.subList(0, frontier)
        val postsById = posts.associateBy(Post::id)
        val lateIds = lateArrivals.mapTo(HashSet(), LateDurationArrival::postId)
        val lateByIndex = lateArrivals.groupBy(LateDurationArrival::beforeIndex)
        fun MutableList<Post>.addLateAt(index: Int) {
            lateByIndex[index].orEmpty().forEach { arrival -> postsById[arrival.postId]?.let(::add) }
        }
        return buildList(frontier + lateArrivals.size) {
            for (index in 0 until frontier) {
                addLateAt(index)
                posts[index].takeUnless { it.id in lateIds }?.let(::add)
            }
            addLateAt(frontier)
        }
    }
}

fun advanceDurationReveal(
    reveal: DurationReveal,
    posts: List<Post>,
    stateByPostId: Map<PostId, MediaDurationState>,
    knownDurationMsByPostId: Map<PostId, Long>,
    window: Int = DEFAULT_DURATION_REVEAL_WINDOW,
): DurationReveal {
    fun isPending(post: Post): Boolean {
        if (!isAnimatedPost(post) || post.id in knownDurationMsByPostId) return false
        return stateByPostId[post.id].isUndecided
    }
    val current = reveal.takeUnless {
        it.frontier > posts.size ||
            (it.frontier > 0 && posts[it.frontier - 1].id != it.frontierAnchor)
    } ?: DurationReveal()

    val arrived = current.awaiting.filter { it in knownDurationMsByPostId }
    val lateArrivals = current.lateArrivals + arrived.map { LateDurationArrival(it, current.frontier) }
    val awaiting = LinkedHashSet(current.awaiting).apply { removeAll(arrived.toSet()) }

    var frontier = current.frontier
    while (frontier < posts.size) {
        val post = posts[frontier]
        if (isPending(post) && !hasEnoughDecidedAfter(posts, frontier, window, ::isPending)) break
        if (isAnimatedPost(post) && post.id !in knownDurationMsByPostId) awaiting += post.id
        frontier += 1
    }
    return DurationReveal(frontier, posts.getOrNull(frontier - 1)?.id, awaiting, lateArrivals)
}

private fun hasEnoughDecidedAfter(
    posts: List<Post>,
    index: Int,
    window: Int,
    isPending: (Post) -> Boolean,
): Boolean {
    var decided = 0
    for (later in index + 1 until posts.size) {
        if (!isPending(posts[later]) && ++decided >= window) return true
    }
    return false
}
