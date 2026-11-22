package com.theoriacodex.app.recommend

import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.LifetimeStatistics
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.SavedSearchRepository
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * What ranks a post's tags for the active profile, every map keyed by [normalizeMatchToken]:
 * - [likedCounts]: how many Likes carry each tag, out of [likes];
 * - [interest]: one point per Like or Codex post carrying the tag, plus [EXPLICIT_WEIGHT] when the profile saved it
 *   as a tag, searches it in a saved search, or searched it at least twice;
 * - [explicit]: the tags with that explicit weight;
 * - [watchedByTag] and [watchedBySource]: how many watched posts of each source carried each tag, out of how many.
 */
data class TagAffinityInputs(
    val likedCounts: Map<String, Int> = emptyMap(),
    val likes: Int = 0,
    val interest: Map<String, Double> = emptyMap(),
    val explicit: Set<String> = emptySet(),
    val watchedByTag: Map<SourceKey, Map<String, Long>> = emptyMap(),
    val watchedBySource: Map<SourceKey, Long> = emptyMap(),
) {
    /** The share of [source]'s watched posts that carried [token], or null before there are enough to say. */
    fun watchedShare(source: SourceKey, token: String): Double? {
        val watched = watchedBySource[source] ?: 0L
        if (watched < MIN_WATCHED_FOR_SHARE) return null
        return (watchedByTag[source]?.get(token) ?: 0L).toDouble() / watched
    }

    companion object {
        val Empty = TagAffinityInputs()
    }
}

/**
 * One of a post's tags in the post's own spelling. [score] grows with the profile's [interest] and shrinks as the tag
 * gets common in what it watches; a [universal] tag (on most watched posts of its source, or on nearly every post of
 * the feed at hand) narrows nothing and is offered neither to follow nor to hide.
 */
data class TagAffinity(val tag: String, val interest: Double, val score: Double, val universal: Boolean)

/** Builds [TagAffinityInputs] from the profile's Likes, Codex posts, saved tags and searches, and its statistics. */
fun tagAffinityInputs(
    likes: List<LikedPost>,
    codexPosts: List<Post>,
    savedTags: Collection<String>,
    savedSearches: List<SavedSearchEntry>,
    statistics: LifetimeStatistics,
): TagAffinityInputs {
    val likedCounts = likes.map { it.tags }.tokenCounts()
    val codexCounts = codexPosts.map { it.canonicalTags.ifEmpty { it.rawTags } }.tokenCounts()
    val searchedTwice = statistics.searchesByTag.entries
        .groupBy({ normalizeMatchToken(it.key.term.value) }, { it.value })
        .filterValues { counts -> counts.sum() >= MIN_SEARCHES_FOR_INTEREST }.keys
    val explicit = (savedTags + savedSearches.flatMap { it.search.query.includeTags }).map(::normalizeMatchToken)
        .plus(searchedTwice).filterTo(mutableSetOf(), String::isNotBlank)
    val interest = (likedCounts.keys + codexCounts.keys + explicit).associateWith { token ->
        (likedCounts[token] ?: 0) + (codexCounts[token] ?: 0) + if (token in explicit) EXPLICIT_WEIGHT else 0.0
    }
    val watchedByTag = statistics.watchedByTag.entries.groupBy({ it.key.source }) { it }
        .mapValues { (_, entries) ->
            entries.groupingBy { normalizeMatchToken(it.key.tag) }.fold(0L) { total, entry -> total + entry.value }
        }
    return TagAffinityInputs(likedCounts, likes.size, interest, explicit, watchedByTag, statistics.watchedBySource)
}

/** The active profile's [TagAffinityInputs], following profile switches; a read failure gives no inputs. */
@OptIn(ExperimentalCoroutinesApi::class)
fun observeTagAffinityInputs(
    settings: SettingsRepository,
    likes: LikesRepository,
    codex: CodexRepository,
    savedSearches: SavedSearchRepository,
    statistics: StatisticsRepository,
): Flow<TagAffinityInputs> {
    val profile = settings.observeSettings()
        .map { it.activeProfileId to it.favoriteTagsByProfile[it.activeProfileId].orEmpty().flatMap { entry -> entry.tags } }
        .distinctUntilChanged()
    val activeProfile = profile.map { it.first }.distinctUntilChanged()
    val profileLikes = activeProfile.flatMapLatest(likes::observeLikes)
    // The profile's own Codices; its Likes collection is left out, since Likes already count once.
    val codexPosts = combine(activeProfile, codex.observeCodices()) { profileId, codices ->
        codices.mapNotNullTo(mutableSetOf()) { entry ->
            entry.codexId.takeIf { id -> ProfileLibraryIds.belongsTo(id, profileId) && !id.startsWith(ProfileLibraryIds.LIKES_PREFIX) }
        }
    }.distinctUntilChanged().flatMapLatest(codex::observeLibraryPosts)
    return combine(profile, profileLikes, codexPosts, savedSearches.observeSavedSearches(), statistics.observeStatistics()) {
            (_, savedTags), liked, posts, searches, stats ->
        tagAffinityInputs(liked, posts, savedTags, searches, stats)
    }.distinctUntilChanged().catch { emit(TagAffinityInputs.Empty) }.flowOn(Dispatchers.Default)
}

/**
 * [post]'s tags, most characteristic of the profile first: Trails take the first non-universal ones as chips. Tags on
 * at least [UNIVERSAL_FEED_SHARE] of the feed at hand are universal there; see [feedTagShare]. Ties keep the post's
 * tag order.
 */
fun tagAffinities(post: Post, inputs: TagAffinityInputs, feedShare: Map<String, Double> = emptyMap()): List<TagAffinity> =
    post.canonicalTags.ifEmpty { post.rawTags }
        .filter(String::isNotBlank)
        .distinctBy(::normalizeMatchToken)
        .map { tag -> inputs.affinity(post.id.source, tag, feedShare) }
        .sortedByDescending(TagAffinity::score)

private fun TagAffinityInputs.affinity(source: SourceKey, tag: String, feedShare: Map<String, Double>): TagAffinity {
    val token = normalizeMatchToken(tag)
    val interest = interest[token] ?: 0.0
    val watched = watchedShare(source, token)
    // Before enough watched posts, a tag on nearly every Like stands in for one on nearly everything watched.
    val likedShare = if (likes >= MIN_LIKES_FOR_SHARE) (likedCounts[token] ?: 0).toDouble() / likes else 0.0
    val common = if (watched != null) watched >= UNIVERSAL_WATCHED_SHARE else likedShare > UNIVERSAL_LIKED_SHARE
    val universal = (common && token !in explicit) || (feedShare[token] ?: 0.0) >= UNIVERSAL_FEED_SHARE
    return TagAffinity(tag, interest, (interest + UNKNOWN_TAG_INTEREST) * (1.0 - (watched ?: 0.0)), universal)
}

/** Each tag's share of [posts], or nothing below [MIN_FEED_POSTS], where a share says little. */
fun feedTagShare(posts: List<Post>): Map<String, Double> {
    if (posts.size < MIN_FEED_POSTS) return emptyMap()
    return posts.map { it.canonicalTags.ifEmpty { it.rawTags } }.tokenCounts().mapValues { (_, count) -> count.toDouble() / posts.size }
}

/** Counts each tag token once per list. */
private fun List<List<String>>.tokenCounts(): Map<String, Int> =
    flatMap { tags -> tags.map(::normalizeMatchToken).filter(String::isNotBlank).distinct() }.groupingBy { it }.eachCount()

/** An explicit choice (a saved tag, a saved search's tag, a repeated search) counts as this many Likes. */
const val EXPLICIT_WEIGHT = 3.0
private const val MIN_SEARCHES_FOR_INTEREST = 2

/** Lets rarity order tags the profile never touched, so an unfamiliar post leads with its least common tags. */
private const val UNKNOWN_TAG_INTEREST = 0.5
private const val MIN_WATCHED_FOR_SHARE = 20L
private const val UNIVERSAL_WATCHED_SHARE = 0.5
private const val MIN_LIKES_FOR_SHARE = 4
private const val UNIVERSAL_LIKED_SHARE = 0.85
private const val MIN_FEED_POSTS = 10
private const val UNIVERSAL_FEED_SHARE = 0.9
