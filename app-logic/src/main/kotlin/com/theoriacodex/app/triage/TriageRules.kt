package com.theoriacodex.app.triage

import com.theoriacodex.app.recommend.TagAffinityInputs
import com.theoriacodex.app.recommend.isImageTextTag
import com.theoriacodex.app.recommend.tagAffinities
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.recommendation.crossSourceTagKey
import com.theoriacodex.domain.recommendation.sharesBooruVocabulary
import com.theoriacodex.domain.tags.normalizeMatchToken
import com.theoriacodex.domain.vision.PictureTags
import com.theoriacodex.domain.vision.PredictedTagUse
import com.theoriacodex.domain.vision.tokens
import kotlin.math.abs

enum class SwipeDirection { Right, Left, Up, Down }

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
        .sortedWith(
            compareBy(
                { it.interest },
                { normalizeMatchToken(it.tag).let { token -> token in nonContent || isImageTextTag(token) } },
                { order[normalizeMatchToken(it.tag)] },
            ),
        )
        .take(limit)
        .map { it.tag }
}

private fun PostTaxonomyTerm.isContentTag(): Boolean =
    facet == SearchFacet.TAG && sourceNamespace?.lowercase() !in NON_CONTENT_NAMESPACES

private val NON_CONTENT_NAMESPACES =
    setOf("artist", "character", "copyright", "parody", "group", "language", "category", "meta", "rating")

const val HIDE_CANDIDATE_LIMIT = 3

fun Post.hiddenBy(hidden: Map<SourceKey, Set<String>>, pictures: PictureTags = PictureTags.None): Boolean {
    if (hidden.isEmpty()) return false
    fun meets(tags: Iterable<String>, booru: Boolean): Boolean {
        val applies = hidden.entries.filter { (source, _) -> source == id.source || booru && source.sharesBooruVocabulary }
            .flatMapTo(HashSet()) { (_, tokens) -> tokens.map(::crossSourceTagKey) }
        return applies.isNotEmpty() && tags.any { crossSourceTagKey(it) in applies }
    }
    if (meets(canonicalTags + rawTags, id.source.sharesBooruVocabulary)) return true
    if (!needsPictureCheck(pictures.library.value.baseRates.keys)) return false
    return meets(pictures.known(id)?.tokens(PredictedTagUse.HIDE)?.keys ?: return false, booru = true)
}

fun Post.needsPictureCheck(vocabulary: Set<String>): Boolean = vocabulary.isEmpty() ||
    (canonicalTags + rawTags).mapTo(HashSet(), ::normalizeMatchToken).count { it in vocabulary } < WELL_TAGGED

const val WELL_TAGGED = 10

private const val VELOCITY_REACH_SECONDS = 0.1f
