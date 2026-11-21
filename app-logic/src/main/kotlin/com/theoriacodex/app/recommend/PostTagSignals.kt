package com.theoriacodex.app.recommend

import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.tags.normalizeMatchToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** How many of a profile's Likes carry each tag (keyed by [normalizeMatchToken]), out of [likes] Likes. */
data class LikedTagCounts(val counts: Map<String, Int>, val likes: Int) {
    companion object {
        val Empty = LikedTagCounts(emptyMap(), 0)
    }
}

/** One of a post's tags, in the post's own spelling, and how many Likes carry it. */
data class PostTagSignal(val tag: String, val likedCount: Int)

/** Counts each tag once per Like. */
fun likedTagCounts(likes: List<LikedPost>): LikedTagCounts = LikedTagCounts(
    counts = likes.flatMap { like -> like.tags.map(::normalizeMatchToken).filter(String::isNotBlank).distinct() }
        .groupingBy { it }.eachCount(),
    likes = likes.size,
)

/** The active profile's [LikedTagCounts], following profile switches; a read failure gives no counts. */
@OptIn(ExperimentalCoroutinesApi::class)
fun observeLikedTagCounts(settings: SettingsRepository, likes: LikesRepository): Flow<LikedTagCounts> =
    settings.observeSettings().map { it.activeProfileId }.distinctUntilChanged()
        .flatMapLatest { profileId -> likes.observeLikes(profileId).map(::likedTagCounts) }
        .distinctUntilChanged()
        .catch { emit(LikedTagCounts.Empty) }

/**
 * [post]'s tags from most to least characteristic of the profile's Likes: Trails take the first for "follow
 * this tag" chips, the triage deck the last for "hide this tag". A tag on nearly every Like says little, so it
 * ranks as if on a quarter as many. Ties keep the post's tag order; nothing is excluded, callers filter.
 */
fun postTagSignals(post: Post, likedTagCounts: LikedTagCounts): List<PostTagSignal> {
    val signals = post.canonicalTags.ifEmpty { post.rawTags }
        .filter(String::isNotBlank)
        .distinctBy(::normalizeMatchToken)
        .map { tag -> PostTagSignal(tag, likedTagCounts.counts[normalizeMatchToken(tag)] ?: 0) }
    return signals.sortedByDescending { signal -> signal.weight(likedTagCounts.likes) }
}

private fun PostTagSignal.weight(likes: Int): Double {
    val ubiquitous = likes >= MIN_LIKES_FOR_UBIQUITY && likedCount.toDouble() / likes > UBIQUITY_SHARE
    return likedCount * if (ubiquitous) UBIQUITY_PENALTY else 1.0
}

private const val MIN_LIKES_FOR_UBIQUITY = 4
private const val UBIQUITY_SHARE = 0.85
private const val UBIQUITY_PENALTY = 0.25
