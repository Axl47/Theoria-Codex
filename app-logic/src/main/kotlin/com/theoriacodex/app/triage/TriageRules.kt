package com.theoriacodex.app.triage

import com.theoriacodex.app.recommend.TagAffinityInputs
import com.theoriacodex.app.recommend.tagAffinities
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
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
 * The tags the deck offers to hide for [post], at most [limit]: the ones the profile shows least interest in first,
 * never a universal tag (see [tagAffinities]), since hiding one would hide nearly everything. Among equals, which on an unfamiliar post is every tag, a content tag comes before one the post's taxonomy marks
 * as an artist, character, series, group, type, language, meta or rating tag, and then the post's own order holds.
 * [excluded] holds tags that must not be offered (the deck's own query and tags already hidden), in any spelling.
 */
fun hideCandidates(
    post: Post,
    affinity: TagAffinityInputs,
    excluded: Collection<String>,
    limit: Int = HIDE_CANDIDATE_LIMIT,
): List<String> {
    val skipped = excluded.mapTo(mutableSetOf(), ::normalizeMatchToken)
    val nonContent = post.taxonomy.filterNot(PostTaxonomyTerm::isContentTag).mapTo(mutableSetOf()) { normalizeMatchToken(it.value) }
    val order = post.canonicalTags.ifEmpty { post.rawTags }.map(::normalizeMatchToken).distinct()
        .withIndex().associate { it.value to it.index }
    return tagAffinities(post, affinity)
        .filterNot { it.universal || normalizeMatchToken(it.tag) in skipped }
        .sortedWith(compareBy({ it.interest }, { normalizeMatchToken(it.tag) in nonContent }, { order[normalizeMatchToken(it.tag)] }))
        .take(limit)
        .map { it.tag }
}

/** A general tag about what the post shows, rather than who made it, what it is from, or how it is filed. */
private fun PostTaxonomyTerm.isContentTag(): Boolean =
    facet == SearchFacet.TAG && sourceNamespace?.lowercase() !in NON_CONTENT_NAMESPACES

/** Tag namespaces sources use for tags that say nothing about the content. */
private val NON_CONTENT_NAMESPACES =
    setOf("artist", "character", "copyright", "parody", "group", "language", "category", "meta", "rating")

const val HIDE_CANDIDATE_LIMIT = 3

/** Whether [this] post carries one of [tokens], which are spelled as [normalizeMatchToken] spells them. */
fun Post.carriesAnyTag(tokens: Set<String>): Boolean =
    tokens.isNotEmpty() && (canonicalTags + rawTags).any { tag -> normalizeMatchToken(tag) in tokens }

/** A drag's speed counts as this much extra travel per px/s when picking its axis. */
private const val VELOCITY_REACH_SECONDS = 0.1f
