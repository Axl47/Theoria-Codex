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
import kotlin.math.ln

/**
 * What ranks a post's tags for the active profile, every map keyed by [normalizeMatchToken]:
 * - [likedCounts]: how many Likes carry each tag, out of [likes];
 * - [interest]: one point per Like or Codex post carrying the tag, plus [EXPLICIT_WEIGHT] when the profile saved it
 *   as a tag, searches it in a saved search, or searched it at least twice;
 * - [explicit]: the tags with that explicit weight;
 * - [watchedByTag] and [watchedBySource]: how many watched posts of each source carried each tag, out of how many;
 * - [keptByTag] and [keptBySource]: the same for the posts the profile kept (Liked or put in a Codex, each once).
 */
data class TagAffinityInputs(
    val likedCounts: Map<String, Int> = emptyMap(),
    val likes: Int = 0,
    val interest: Map<String, Double> = emptyMap(),
    val explicit: Set<String> = emptySet(),
    val watchedByTag: Map<SourceKey, Map<String, Long>> = emptyMap(),
    val watchedBySource: Map<SourceKey, Long> = emptyMap(),
    val keptByTag: Map<SourceKey, Map<String, Int>> = emptyMap(),
    val keptBySource: Map<SourceKey, Int> = emptyMap(),
) {
    /** The share of [source]'s watched posts that carried [token], or null before there are enough to say. */
    fun watchedShare(source: SourceKey, token: String): Double? {
        val watched = watchedBySource[source] ?: 0L
        if (watched < MIN_WATCHED_FOR_SHARE) return null
        return (watchedByTag[source]?.get(token) ?: 0L).toDouble() / watched
    }

    /**
     * How many times more often [token] is on the [source] posts the profile kept than on those it watched: about 1 for
     * a tag on everything alike (blush), far above 1 for what the profile seeks out (a character it follows). Null
     * before the source has enough of either to say.
     */
    fun lift(source: SourceKey, token: String): Double? {
        val watched = watchedBySource[source] ?: 0L
        val kept = keptBySource[source] ?: 0
        if (watched < MIN_WATCHED_FOR_SHARE || kept < MIN_KEPT_FOR_LIFT) return null
        val keptShare = ((keptByTag[source]?.get(token) ?: 0) + LIFT_PSEUDO_COUNT) / (kept + 1.0)
        val watchedShare = maxOf((watchedByTag[source]?.get(token) ?: 0L).toDouble(), LIFT_PSEUDO_COUNT) / watched
        return keptShare / watchedShare
    }

    companion object {
        val Empty = TagAffinityInputs()
    }
}

/**
 * One of a post's tags in the post's own spelling. [score] ranks how characteristic it is of the profile: by its
 * [TagAffinityInputs.lift] once the source has the history, else by [interest] shrinking as the tag gets common in what
 * the profile watches. A [universal] tag (on most watched posts of its source, or on nearly every post of the feed at
 * hand) narrows nothing and is offered neither to follow nor to hide. An [informative] tag is worth offering to follow:
 * one the profile chose, one clearly more common in what it kept than in what it watched, or one too rare in what it
 * watches to tell yet.
 */
data class TagAffinity(
    val tag: String,
    val interest: Double,
    val score: Double,
    val universal: Boolean,
    val informative: Boolean = !universal,
)

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
    // A post both Liked and in a Codex is one kept post.
    val kept = (likes.map { it.postId to it.tags } + codexPosts.map { it.id to it.canonicalTags.ifEmpty { it.rawTags } })
        .distinctBy { it.first }.groupBy({ it.first.source }, { it.second })
    // One search counts once under every source it ran on, so a tag's busiest source is its search count.
    val searchedTwice = statistics.searchesByTag.entries
        .groupBy({ normalizeMatchToken(it.key.term.value) }, { it.value })
        .filterValues { counts -> counts.max() >= MIN_SEARCHES_FOR_INTEREST }.keys
    val explicit = (savedTags + savedSearches.flatMap { it.search.query.includeTags }).map(::normalizeMatchToken)
        .plus(searchedTwice).filterTo(mutableSetOf(), String::isNotBlank)
    val interest = (likedCounts.keys + codexCounts.keys + explicit).associateWith { token ->
        (likedCounts[token] ?: 0) + (codexCounts[token] ?: 0) + if (token in explicit) EXPLICIT_WEIGHT else 0.0
    }
    val watchedByTag = statistics.watchedByTag.entries.groupBy({ it.key.source }) { it }
        .mapValues { (_, entries) ->
            entries.groupingBy { normalizeMatchToken(it.key.tag) }.fold(0L) { total, entry -> total + entry.value }
        }
    return TagAffinityInputs(
        likedCounts, likes.size, interest, explicit, watchedByTag, statistics.watchedBySource,
        keptByTag = kept.mapValues { (_, tags) -> tags.tokenCounts() },
        keptBySource = kept.mapValues { (_, tags) -> tags.size },
    )
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
 * [post]'s tags, most characteristic of the profile first: Trails take the first informative ones as chips. Tags on at
 * least [UNIVERSAL_FEED_SHARE] of the feed at hand are universal there; see [feedTagShare]. Ties keep the post's tag
 * order.
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
    val chosen = token in explicit
    // A tag on most of what the profile watches narrows nothing even when chosen; Likes alone are weaker evidence.
    val common = if (watched != null) watched >= UNIVERSAL_WATCHED_SHARE else likedShare > UNIVERSAL_LIKED_SHARE && !chosen
    val universal = common || (feedShare[token] ?: 0.0) >= UNIVERSAL_FEED_SHARE
    val lift = lift(source, token)
        ?: return TagAffinity(tag, interest, (interest + UNKNOWN_TAG_INTEREST) * (1.0 - (watched ?: 0.0)), universal)
    val (score, worthOffering) = historyScore(source, token, lift, chosen, watched ?: 0.0)
    return TagAffinity(tag, interest, score, universal, informative = !universal && worthOffering)
}

/**
 * A tag's score and whether it is worth offering, on a source with the history for [lift]. It is when [chosen] or
 * clearly more common in what was kept, and also, after those, when too rare in what was watched ([watched], a share)
 * for its absence from what was kept to count against it.
 */
private fun TagAffinityInputs.historyScore(
    source: SourceKey,
    token: String,
    lift: Double,
    chosen: Boolean,
    watched: Double,
): Pair<Double, Boolean> {
    val kept = keptByTag[source]?.get(token) ?: 0
    // How much more common, times how many kept posts say so; a tag kept once is still a guess.
    val score = ln(lift) * ln(1.0 + kept) + if (chosen) EXPLICIT_BONUS else 0.0
    if (chosen || (kept >= MIN_KEPT_FOR_TAG && lift >= MIN_INFORMATIVE_LIFT)) return score to true
    val tooRareToJudge = kept < MIN_KEPT_FOR_TAG && watched * (keptBySource[source] ?: 0) < MIN_KEPT_FOR_TAG
    // Rarest first, below every proven tag.
    return if (tooRareToJudge) -watched to true else score to false
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

/** An explicit choice's score, about that of a tag on ten kept posts at four times its watched rate. */
private const val EXPLICIT_BONUS = 3.0
private const val MIN_SEARCHES_FOR_INTEREST = 2

/** Lets rarity order tags the profile never touched, so an unfamiliar post leads with its least common tags. */
private const val UNKNOWN_TAG_INTEREST = 0.5
private const val MIN_WATCHED_FOR_SHARE = 20L
private const val UNIVERSAL_WATCHED_SHARE = 0.5
private const val MIN_LIKES_FOR_SHARE = 4
private const val UNIVERSAL_LIKED_SHARE = 0.85
private const val MIN_FEED_POSTS = 10
private const val MIN_KEPT_FOR_LIFT = 20
private const val MIN_KEPT_FOR_TAG = 2
private const val MIN_INFORMATIVE_LIFT = 2.0

/** Half a post, so a tag never kept or never watched still has a finite lift. */
private const val LIFT_PSEUDO_COUNT = 0.5
private const val UNIVERSAL_FEED_SHARE = 0.9
