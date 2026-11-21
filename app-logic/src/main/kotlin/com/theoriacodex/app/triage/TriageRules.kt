package com.theoriacodex.app.triage

import com.theoriacodex.app.recommend.LikedTagCounts
import com.theoriacodex.app.recommend.postTagSignals
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.tags.normalizeMatchToken
import kotlin.math.abs

/** The way a triage card was thrown. */
enum class SwipeDirection { Right, Left, Up, Down }

/**
 * Where a drag of the triage card ends up: the direction it commits to, or null when it springs back. Offsets
 * are in pixels with y growing downward, velocities in pixels per second. The axis the card travelled further
 * on, counting what its speed would add, decides the direction; it commits once pulled [commitDistance] or
 * flung at [flingVelocity], and a fling against the pull cancels it.
 */
fun swipeDirection(
    offsetX: Float,
    offsetY: Float,
    velocityX: Float,
    velocityY: Float,
    commitDistance: Float,
    flingVelocity: Float,
): SwipeDirection? {
    val horizontal = abs(offsetX) + abs(velocityX) * VELOCITY_REACH_SECONDS >=
        abs(offsetY) + abs(velocityY) * VELOCITY_REACH_SECONDS
    val offset = if (horizontal) offsetX else offsetY
    val velocity = if (horizontal) velocityX else velocityY
    val flung = abs(velocity) >= flingVelocity
    if (flung && offset * velocity < 0f) return null
    val forward = when {
        abs(offset) >= commitDistance -> offset > 0f
        flung -> velocity > 0f
        else -> return null
    }
    return when {
        horizontal -> if (forward) SwipeDirection.Right else SwipeDirection.Left
        else -> if (forward) SwipeDirection.Down else SwipeDirection.Up
    }
}

/**
 * The tags the deck offers to hide for [post], the ones the profile's Likes show least interest in first, at most
 * [limit]. [excluded] holds tags that must not be offered (the deck's own query and tags already hidden), in any
 * spelling.
 */
fun hideCandidates(
    post: Post,
    likedTagCounts: LikedTagCounts,
    excluded: Collection<String>,
    limit: Int = HIDE_CANDIDATE_LIMIT,
): List<String> {
    val skipped = excluded.mapTo(mutableSetOf(), ::normalizeMatchToken)
    return postTagSignals(post, likedTagCounts).asReversed()
        .map { signal -> signal.tag }
        .filter { tag -> normalizeMatchToken(tag) !in skipped }
        .take(limit)
}

const val HIDE_CANDIDATE_LIMIT = 3

/** Whether [this] post carries one of [tokens], which are spelled as [normalizeMatchToken] spells them. */
fun Post.carriesAnyTag(tokens: Set<String>): Boolean =
    tokens.isNotEmpty() && (canonicalTags + rawTags).any { tag -> normalizeMatchToken(tag) in tokens }

/** A drag's speed counts as this much extra travel per px/s when picking its axis. */
private const val VELOCITY_REACH_SECONDS = 0.1f
