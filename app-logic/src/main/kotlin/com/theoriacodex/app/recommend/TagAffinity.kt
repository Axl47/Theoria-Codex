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
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.recommendation.LikedTagDocument
import com.theoriacodex.domain.tags.normalizeMatchToken
import com.theoriacodex.domain.vision.PredictedTagIndex
import com.theoriacodex.domain.vision.PredictedTagUse
import com.theoriacodex.domain.vision.tokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlin.math.ln

/**
 * What ranks a post's tags for the active profile, every map keyed by [normalizeMatchToken]:
 * - [liked]: each Like's tags, newest first, read from the Library's copy of the post where it holds one, since a Like
 *   saved before every tag was stored kept only some;
 * - [likedCounts]: how many Likes carry each tag, out of [likes];
 * - [interest]: one point per kept post (Liked or put in a Codex, each once) carrying the tag, plus [EXPLICIT_WEIGHT] when the profile saved it
 *   as a tag, searches it in a saved search, or searched it at least twice;
 * - [explicit]: the tags with that explicit weight;
 * - [watchedByTag] and [watchedBySource]: how many watched posts of each source carried each tag, out of how many;
 * - [keptByTag] and [keptBySource]: the same for the posts the profile kept (Liked or put in a Codex, each once);
 * - [pictures]: what the picture tagger predicted on those kept posts.
 */
data class TagAffinityInputs(
    val liked: List<LikedTagDocument> = emptyList(),
    val likedCounts: Map<String, Int> = emptyMap(),
    val likes: Int = 0,
    val interest: Map<String, Double> = emptyMap(),
    val explicit: Set<String> = emptySet(),
    val watchedByTag: Map<SourceKey, Map<String, Long>> = emptyMap(),
    val watchedBySource: Map<SourceKey, Long> = emptyMap(),
    val keptByTag: Map<SourceKey, Map<String, Int>> = emptyMap(),
    val keptBySource: Map<SourceKey, Int> = emptyMap(),
    val pictures: PictureEvidence = PictureEvidence(),
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
        if (!hasHistory(source)) return null
        val keptShare = ((keptByTag[source]?.get(token) ?: 0) + LIFT_PSEUDO_COUNT) / ((keptBySource[source] ?: 0) + 1.0)
        val watchedShare = maxOf((watchedByTag[source]?.get(token) ?: 0L).toDouble(), LIFT_PSEUDO_COUNT) / (watchedBySource[source] ?: 0L)
        return keptShare / watchedShare
    }

    /** Whether [source] has watched and kept enough posts for [lift], and so for [TagAffinity.proven]. */
    fun hasHistory(source: SourceKey): Boolean =
        (watchedBySource[source] ?: 0L) >= MIN_WATCHED_FOR_SHARE && (keptBySource[source] ?: 0) >= MIN_KEPT_FOR_LIFT

    companion object {
        val Empty = TagAffinityInputs()
    }
}

/**
 * What the picture tagger saw on the posts the profile kept, whatever their source: [keptByTag] sums each predicted tag's
 * score over the kept posts it was predicted on with [PredictedTagUse.EVIDENCE] confidence ([evidence]), [postsByTag]
 * counts those posts, out of [kept] analysed kept posts, and [baseRates] holds each tag's share of Danbooru posts. Keys
 * are Danbooru tags as [normalizeMatchToken] spells them.
 */
data class PictureEvidence(
    val keptByTag: Map<String, Double> = emptyMap(),
    val postsByTag: Map<String, Int> = emptyMap(),
    val kept: Int = 0,
    val baseRates: Map<String, Double> = emptyMap(),
) {
    /** The predicted tags on enough kept posts ([MIN_KEPT_FOR_TAG]) to be proven by [pictureAffinity]. */
    val candidates: Set<String> get() = postsByTag.filterValues { it >= MIN_KEPT_FOR_TAG }.keys

    companion object {
        /** [index]'s predictions on the posts in [kept]. */
        fun of(kept: Set<PostId>, index: PredictedTagIndex): PictureEvidence {
            val seen = kept.filter { index[it] != null }
            if (seen.isEmpty()) return PictureEvidence()
            val scores = HashMap<String, Double>()
            val posts = HashMap<String, Int>()
            for (id in seen) {
                index.evidence(id).forEach { (token, score) ->
                    scores.merge(token, score, Double::plus)
                    posts.merge(token, 1, Int::plus)
                }
            }
            return PictureEvidence(scores, posts, seen.size, index.baseRates)
        }
    }
}

/**
 * The tags predicted for [id] that may stand as evidence of what its picture shows, by score: confident enough for
 * [PredictedTagUse.EVIDENCE], never text on the image ([isImageTextTag]), and not on half of Danbooru's posts.
 */
fun PredictedTagIndex.evidence(id: PostId): Map<String, Double> =
    get(id)?.tokens(PredictedTagUse.EVIDENCE).orEmpty().filterKeys { !isImageTextTag(it) && (baseRates[it] ?: 0.0) < UNIVERSAL_WATCHED_SHARE }

/**
 * One of a post's tags in the post's own spelling. [score] ranks how characteristic it is of the profile: by its
 * [TagAffinityInputs.lift] once the source has the history, else by [interest] shrinking as the tag gets common in what
 * the profile watches. A [universal] tag (on most watched posts of its source, or on nearly every post of the feed at
 * hand) narrows nothing and is offered neither to follow nor to hide. An [informative] tag is worth offering to follow:
 * one the profile chose, one clearly more common in what it kept than in what it watched, or one too rare in what it
 * watches to tell yet, but never one for text on the image ([isImageTextTag]) or about the file ([isFileTag]) that the
 * profile did not choose. A
 * [proven] tag is informative for the first two reasons, not as a guess: For You seeds lanes only from proven tags.
 */
data class TagAffinity(
    val tag: String,
    val interest: Double,
    val score: Double,
    val universal: Boolean,
    val informative: Boolean = !universal,
    val proven: Boolean = false,
)

/**
 * Builds [TagAffinityInputs] from the profile's Likes, the posts its Library holds ([libraryPosts]: its Codices, the
 * Likes collection among them), its saved tags and searches, and its statistics.
 */
fun tagAffinityInputs(
    likes: List<LikedPost>,
    libraryPosts: List<Post>,
    savedTags: Collection<String>,
    savedSearches: List<SavedSearchEntry>,
    statistics: LifetimeStatistics,
    predicted: PredictedTagIndex = PredictedTagIndex.Empty,
): TagAffinityInputs = keptTagEvidence(likes, libraryPosts, savedTags, savedSearches).withStatistics(statistics, predicted)

/**
 * What [TagAffinityInputs] takes from the profile's library, apart from its statistics: building it reads every kept
 * post's tags, so [shareTagAffinityInputs] rebuilds it only when the library changes, not on every watched post.
 */
private data class KeptTagEvidence(
    val liked: List<LikedTagDocument>,
    val likedCounts: Map<String, Int>,
    val keptCounts: Map<String, Int>,
    val saved: Set<String>,
    val keptByTag: Map<SourceKey, Map<String, Int>>,
    val keptBySource: Map<SourceKey, Int>,
    val keptIds: Set<PostId>,
)

private fun keptTagEvidence(
    likes: List<LikedPost>,
    libraryPosts: List<Post>,
    savedTags: Collection<String>,
    savedSearches: List<SavedSearchEntry>,
): KeptTagEvidence {
    val stored = libraryPosts.associateBy(Post::id)
    // The stored copy is shared with every view of the post; one thinner than what the Like kept does not replace it.
    val liked = likes.map { like ->
        LikedTagDocument(like.postId.source, stored[like.postId]?.let(::trainingTagsFor)?.takeIf { it.size >= like.tags.size } ?: like.tags)
    }
    val likedIds = likes.mapTo(mutableSetOf(), LikedPost::postId)
    // A post both Liked and in a Codex is one kept post.
    val keptPosts = likes.map(LikedPost::postId).zip(liked.map(LikedTagDocument::tags)) +
        stored.values.filter { it.id !in likedIds }.map { it.id to trainingTagsFor(it) }
    val kept = keptPosts.groupBy({ it.first.source }, { it.second })
    return KeptTagEvidence(
        liked = liked,
        likedCounts = liked.map(LikedTagDocument::tags).tokenCounts(),
        keptCounts = keptPosts.map { it.second }.tokenCounts(),
        saved = (savedTags + savedSearches.flatMap { it.search.query.includeTags }).map(::normalizeMatchToken).toSet(),
        keptByTag = kept.mapValues { (_, tags) -> tags.tokenCounts() },
        keptBySource = kept.mapValues { (_, tags) -> tags.size },
        keptIds = keptPosts.mapTo(mutableSetOf()) { it.first },
    )
}

private fun KeptTagEvidence.withStatistics(statistics: LifetimeStatistics, predicted: PredictedTagIndex): TagAffinityInputs {
    // One search counts once under every source it ran on, so a tag's busiest source is its search count.
    val searchedTwice = statistics.searchesByTag.entries
        .groupBy({ normalizeMatchToken(it.key.term.value) }, { it.value })
        .filterValues { counts -> counts.max() >= MIN_SEARCHES_FOR_INTEREST }.keys
    val explicit = (saved + searchedTwice).filterTo(mutableSetOf(), String::isNotBlank)
    val interest = (keptCounts.keys + explicit).associateWith { token ->
        (keptCounts[token] ?: 0) + if (token in explicit) EXPLICIT_WEIGHT else 0.0
    }
    val watchedByTag = statistics.watchedByTag.entries.groupBy({ it.key.source }) { it }
        .mapValues { (_, entries) ->
            entries.groupingBy { normalizeMatchToken(it.key.tag) }.fold(0L) { total, entry -> total + entry.value }
        }
    return TagAffinityInputs(
        liked, likedCounts, liked.size, interest, explicit, watchedByTag, statistics.watchedBySource, keptByTag, keptBySource,
        PictureEvidence.of(keptIds, predicted),
    )
}

/**
 * These inputs with each chosen tag in [spell]'s spelling where it has one. Saved tags and searches carry no source, so
 * For You respells them for the source it fills: a tag saved in Pixiv's Japanese seeds the boorus in English.
 */
fun TagAffinityInputs.respellChosen(spell: (String) -> String?): TagAffinityInputs {
    val chosen = explicit.toMutableSet()
    val weights = interest.toMutableMap()
    for (tag in explicit) {
        val spelled = spell(tag)?.let(::normalizeMatchToken)?.takeIf { it.isNotBlank() && it != tag } ?: continue
        chosen -= tag
        weights.merge(tag, -EXPLICIT_WEIGHT, Double::plus)
        if (chosen.add(spelled)) weights.merge(spelled, EXPLICIT_WEIGHT, Double::plus)
    }
    return copy(explicit = chosen, interest = weights)
}

/**
 * The active profile's [TagAffinityInputs] for the app session, built once by [shareTagAffinityInputs]: For You's lanes
 * ask [forProfile]; Trails and cards read [latest].
 */
class SharedTagAffinity internal constructor(private val byProfile: SharedFlow<Pair<String, TagAffinityInputs>>, scope: CoroutineScope) {
    /** The newest inputs, empty until the first are built; for a moment after a profile switch, the last profile's. */
    val latest: StateFlow<TagAffinityInputs> = byProfile.map { it.second }.stateIn(scope, SharingStarted.Lazily, TagAffinityInputs.Empty)

    /** [profileId]'s inputs, waiting out a profile switch that has not reached them yet. */
    suspend fun forProfile(profileId: String): TagAffinityInputs = byProfile.first { it.first == profileId }.second
}

/**
 * Builds [SharedTagAffinity] in [scope], from its first reader on, following profile switches. A failed read gives the
 * profile empty inputs and is retried with backoff. [predicted] is the picture tagger's index; only its predictions on
 * kept posts count, so analysing a For You candidate changes nothing here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun shareTagAffinityInputs(
    scope: CoroutineScope,
    settings: SettingsRepository,
    likes: LikesRepository,
    codex: CodexRepository,
    savedSearches: SavedSearchRepository,
    statistics: StatisticsRepository,
    predicted: Flow<PredictedTagIndex> = flowOf(PredictedTagIndex.Empty),
): SharedTagAffinity {
    // Only the counters affinity reads, so a write of usage time or any other counter rebuilds nothing.
    val watched = statistics.observeStatistics()
        .map { LifetimeStatistics(watchedBySource = it.watchedBySource, watchedByTag = it.watchedByTag, searchesByTag = it.searchesByTag) }
        .distinctUntilChanged()
    // Everything kept is read under one profile, so a switch never pairs one profile's saved tags with another's Likes.
    val byProfile = settings.observeSettings().map { it.activeProfileId }.distinctUntilChanged().flatMapLatest { profileId ->
        val savedTags = settings.observeSettings()
            .map { it.favoriteTagsByProfile[profileId].orEmpty().flatMap { entry -> entry.tags } }.distinctUntilChanged()
        // The profile's own Codices and its Likes collection, whose copies hold each Liked post's every tag.
        val libraryPosts = codex.observeCodices().map { codices ->
            codices.mapNotNullTo(mutableSetOf()) { entry -> entry.codexId.takeIf { id -> ProfileLibraryIds.belongsTo(id, profileId) } }
        }.distinctUntilChanged().flatMapLatest(codex::observeLibraryPosts)
        val kept = combine(savedTags, likes.observeLikes(profileId), libraryPosts, savedSearches.observeSavedSearches()) {
                tags, liked, posts, searches ->
            keptTagEvidence(liked, posts, tags, searches)
        }.distinctUntilChanged()
        combine(kept, watched, predicted) { evidence, statistics, index -> profileId to evidence.withStatistics(statistics, index) }
            .retryWhen { _, attempt ->
                emit(profileId to TagAffinityInputs.Empty)
                delay(retryDelayMs(attempt))
                true
            }
    }.retryWhen { _, attempt ->
        delay(retryDelayMs(attempt))
        true
    }
    return SharedTagAffinity(byProfile.distinctUntilChanged().flowOn(Dispatchers.Default).shareIn(scope, SharingStarted.Lazily, replay = 1), scope)
}

/** 1 s after the first failure, doubling to about a minute. */
private fun retryDelayMs(attempt: Long): Long = RETRY_BASE_MS shl attempt.coerceAtMost(RETRY_MAX_DOUBLINGS).toInt()

private const val RETRY_BASE_MS = 1_000L
private const val RETRY_MAX_DOUBLINGS = 6L

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

/** How characteristic [tag] is of the profile on [source]; [tagAffinities] ranks a post's tags by it, For You its lanes. */
fun TagAffinityInputs.affinity(source: SourceKey, tag: String, feedShare: Map<String, Double> = emptyMap()): TagAffinity {
    val token = normalizeMatchToken(tag)
    val interest = interest[token] ?: 0.0
    val watched = watchedShare(source, token)
    // Before enough watched posts, a tag on nearly every Like stands in for one on nearly everything watched.
    val likedShare = if (likes >= MIN_LIKES_FOR_SHARE) (likedCounts[token] ?: 0).toDouble() / likes else 0.0
    val chosen = token in explicit
    // A tag on most of what the profile watches narrows nothing even when chosen; Likes alone are weaker evidence.
    val common = if (watched != null) watched >= UNIVERSAL_WATCHED_SHARE else likedShare > UNIVERSAL_LIKED_SHARE && !chosen
    val universal = common || (feedShare[token] ?: 0.0) >= UNIVERSAL_FEED_SHARE
    // Text drawn on the image, or how the file was made, says nothing about what it shows, though either can be as
    // specific as a series in what the profile keeps.
    val offerable = !universal && (chosen || !(isImageTextTag(token) || isFileTag(token)))
    val lift = lift(source, token) ?: return TagAffinity(
        tag, interest, (interest + UNKNOWN_TAG_INTEREST) * (1.0 - (watched ?: 0.0)), universal, informative = offerable,
        proven = offerable && chosen,
    )
    val kept = keptByTag[source]?.get(token) ?: 0
    val proven = chosen || (kept >= MIN_KEPT_FOR_TAG && lift >= MIN_INFORMATIVE_LIFT)
    val (score, worthOffering) = historyScore(source, kept, lift, chosen, proven, watched ?: 0.0)
    return TagAffinity(tag, interest, score, universal, informative = offerable && worthOffering, proven = offerable && proven)
}

/**
 * How characteristic a predicted tag is of the profile on [source], for For You's lanes: [booruToken] is the tag in
 * Danbooru's words, [sourceToken] in [source]'s. Its kept share (see [PictureEvidence]) is compared with its share of what
 * the profile watched on [source], never below its share of Danbooru posts, so a tag the source's providers rarely write
 * cannot look sought after. Before [source] has enough watched posts the Danbooru share stands alone and must be beaten
 * [COLD_PICTURE_LIFT] times. Proven like a provider tag: on [MIN_KEPT_FOR_TAG] kept posts at the lift, out of at least
 * [MIN_KEPT_FOR_LIFT] analysed kept posts, never universal (on more than [UNIVERSAL_LIKED_SHARE] of them, too), never
 * text on the image and never about the file ([isFileTag]); a profile that chose such a tag gets it through [affinity].
 * The kept share weighs each post by its score.
 */
fun TagAffinityInputs.pictureAffinity(source: SourceKey, booruToken: String, sourceToken: String): TagAffinity {
    val kept = pictures.keptByTag[booruToken] ?: 0.0
    val posts = pictures.postsByTag[booruToken] ?: 0
    val watched = watchedShare(source, normalizeMatchToken(sourceToken))
    val baseRate = pictures.baseRates[booruToken] ?: 0.0
    // Universal like a provider tag: common where the profile watches, or on nearly every post it kept.
    val universal = maxOf(watched ?: 0.0, baseRate) >= UNIVERSAL_WATCHED_SHARE || posts > UNIVERSAL_LIKED_SHARE * pictures.kept
    val floor = LIFT_PSEUDO_COUNT / maxOf(watchedBySource[source] ?: 0L, MIN_WATCHED_FOR_SHARE)
    val lift = (kept + LIFT_PSEUDO_COUNT) / (pictures.kept + 1.0) / maxOf(watched ?: 0.0, baseRate, floor)
    val proven = !universal && !isImageTextTag(booruToken) && !isFileTag(booruToken) && pictures.kept >= MIN_KEPT_FOR_LIFT && posts >= MIN_KEPT_FOR_TAG &&
        lift >= if (watched != null) MIN_INFORMATIVE_LIFT else COLD_PICTURE_LIFT
    return TagAffinity(sourceToken, interest = kept, score = ln(lift) * ln(1.0 + kept), universal, informative = proven, proven = proven)
}

/**
 * A tag's score and whether it is worth offering, on a source with the history for [lift]. It is when [proven], and
 * also, after those, when too rare in what was watched ([watched], a share) for its absence from what was kept ([kept]
 * posts) to count against it.
 */
private fun TagAffinityInputs.historyScore(
    source: SourceKey,
    kept: Int,
    lift: Double,
    chosen: Boolean,
    proven: Boolean,
    watched: Double,
): Pair<Double, Boolean> {
    // How much more common, times how many kept posts say so; a tag kept once is still a guess.
    val score = ln(lift) * ln(1.0 + kept) + if (chosen) EXPLICIT_BONUS else 0.0
    if (proven) return score to true
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

/**
 * Whether [token] ([normalizeMatchToken] spelling) tags text drawn on the image (a name, a watermark, a handle or logo)
 * rather than what the image shows. Sources file these as ordinary tags, and on what a profile keeps they can be as much
 * more common as a series it follows, so no count tells them apart. Handles and logos are matched by their ending, so a
 * new platform's need no change.
 */
fun isImageTextTag(token: String): Boolean = token in IMAGE_TEXT_TAGS || IMAGE_TEXT_ENDINGS.any(token::endsWith)

private val IMAGE_TEXT_TAGS = setOf("artist name", "character name", "copyright name", "signature", "watermark", "web address", "dated")
private val IMAGE_TEXT_ENDINGS = listOf(" username", " logo", " watermark")

/**
 * Whether [token] ([normalizeMatchToken] spelling) tags the file rather than what it shows: its medium (3d), motion
 * (animated gif), sound (audible music), length, resolution, or how the source files it (commentary). A profile that
 * keeps mostly animations keeps these far more often than it watches them, so, like [isImageTextTag], no count tells them
 * apart from an interest. Families of these are matched by their wording, so a new medium or length needs no change.
 */
fun isFileTag(token: String): Boolean =
    token in FILE_TAGS || FILE_TAG_PREFIXES.any(token::startsWith) || FILE_TAG_ENDINGS.any(token::endsWith) ||
        (token.startsWith("bad ") && token.endsWith(" id"))

private val FILE_TAGS = setOf(
    // Medium
    "2d", "3d", "3dcg", "3d animation", "3d model", "cgi", "render", "source filmmaker", "sfm", "mmd", "blender", "koikatsu",
    // Motion
    "animated", "animation", "gif", "webm", "mp4", "video", "loop", "looping animation", "ugoira", "うごイラ", "60fps",
    // Sound
    "sound", "audio", "has audio", "no audio", "no sound", "sound effects", "voice acted", "voice acting",
    // Resolution and filing
    "lowres", "highres", "absurdres", "incredibly absurdres", "4k", "huge filesize", "tagme", "commentary", "translated",
    "partially translated", "md5 mismatch", "duplicate",
)
private val FILE_TAG_PREFIXES = listOf("animated ", "audible ", "longer than ", "shorter than ")
private val FILE_TAG_ENDINGS = listOf(" (medium)", " (software)", " (artwork)", " commentary", " request")
private const val MIN_KEPT_FOR_LIFT = 20
private const val MIN_KEPT_FOR_TAG = 2
private const val MIN_INFORMATIVE_LIFT = 2.0

/** A predicted tag compared only with Danbooru as a whole needs a wider margin: the profile's sources are not Danbooru. */
private const val COLD_PICTURE_LIFT = 4.0

/** Half a post, so a tag never kept or never watched still has a finite lift. */
private const val LIFT_PSEUDO_COUNT = 0.5
private const val UNIVERSAL_FEED_SHARE = 0.9
