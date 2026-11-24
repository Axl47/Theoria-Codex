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

data class TagAffinityInputs(
    val liked: List<LikedTagDocument> = emptyList(),
    val likedCounts: Map<String, Int> = emptyMap(),
    val likes: Int = 0,
    val interest: Map<String, Double> = emptyMap(),
    val explicit: Set<String> = emptySet(),
    val watchedByTag: Map<SourceKey, Map<String, Long>> = emptyMap(),
    val watchedBySource: Map<SourceKey, Long> = emptyMap(),
    val watchedTagFloors: Map<SourceKey, Long> = emptyMap(),
    val keptByTag: Map<SourceKey, Map<String, Int>> = emptyMap(),
    val keptBySource: Map<SourceKey, Int> = emptyMap(),
    val pictures: PictureEvidence = PictureEvidence(),
) {
    fun watchedShare(source: SourceKey, token: String): Double? = share(source, watchedByTag[source]?.get(token) ?: 0L)

    fun watchedShareAtMost(source: SourceKey, token: String): Double? = share(source, watchedAtMost(source, token))

    fun lift(source: SourceKey, token: String): Double? {
        if (!hasHistory(source)) return null
        val keptShare = ((keptByTag[source]?.get(token) ?: 0) + LIFT_PSEUDO_COUNT) / ((keptBySource[source] ?: 0) + 1.0)
        val watchedShare = maxOf(watchedAtMost(source, token).toDouble(), LIFT_PSEUDO_COUNT) / (watchedBySource[source] ?: 0L)
        return keptShare / watchedShare
    }

    private fun watchedAtMost(source: SourceKey, token: String): Long = watchedByTag[source]?.get(token) ?: watchedTagFloors[source] ?: 0L

    private fun share(source: SourceKey, count: Long): Double? {
        val watched = watchedBySource[source] ?: 0L
        return if (watched < MIN_WATCHED_FOR_SHARE) null else count.toDouble() / watched
    }

    fun hasHistory(source: SourceKey): Boolean =
        (watchedBySource[source] ?: 0L) >= MIN_WATCHED_FOR_SHARE && (keptBySource[source] ?: 0) >= MIN_KEPT_FOR_LIFT

    companion object {
        val Empty = TagAffinityInputs()
    }
}

data class PictureEvidence(
    val keptByTag: Map<String, Double> = emptyMap(),
    val postsByTag: Map<String, Int> = emptyMap(),
    val kept: Int = 0,
    val baseRates: Map<String, Double> = emptyMap(),
) {
    val candidates: Set<String> get() = postsByTag.filterValues { it >= MIN_KEPT_FOR_TAG }.keys

    companion object {
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

fun PredictedTagIndex.evidence(id: PostId): Map<String, Double> =
    get(id)?.tokens(PredictedTagUse.EVIDENCE).orEmpty().filterKeys { !isImageTextTag(it) && (baseRates[it] ?: 0.0) < UNIVERSAL_WATCHED_SHARE }

data class TagAffinity(
    val tag: String,
    val interest: Double,
    val score: Double,
    val universal: Boolean,
    val informative: Boolean = !universal,
    val proven: Boolean = false,
)

fun tagAffinityInputs(
    likes: List<LikedPost>,
    libraryPosts: List<Post>,
    savedTags: Collection<String>,
    savedSearches: List<SavedSearchEntry>,
    statistics: LifetimeStatistics,
    predicted: PredictedTagIndex = PredictedTagIndex.Empty,
): TagAffinityInputs = keptTagEvidence(likes, libraryPosts, savedTags, savedSearches).withStatistics(statistics, predicted)

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
    val likedPosts = likes.map { like ->
        like.postId to (stored[like.postId]?.let(::trainingTagsFor).orEmpty() + like.tags).distinctBy(::normalizeMatchToken)
    }
    val liked = likedPosts.map { (id, tags) -> LikedTagDocument(id.source, tags) }
    val likedIds = likes.mapTo(HashSet(), LikedPost::postId)
    val keptPosts = likedPosts + stored.values.filter { it.id !in likedIds }.map { it.id to trainingTagsFor(it) }
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
        liked, likedCounts, liked.size, interest, explicit, watchedByTag, statistics.watchedBySource, statistics.watchedTagFloors,
        keptByTag, keptBySource, PictureEvidence.of(keptIds, predicted),
    )
}

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

class SharedTagAffinity internal constructor(private val byProfile: SharedFlow<Pair<String, TagAffinityInputs>>, scope: CoroutineScope) {
    val latest: StateFlow<TagAffinityInputs> = byProfile.map { it.second }.stateIn(scope, SharingStarted.Lazily, TagAffinityInputs.Empty)

    suspend fun forProfile(profileId: String): TagAffinityInputs = byProfile.first { it.first == profileId }.second
}

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
    val watched = statistics.observeStatistics()
        .map {
            LifetimeStatistics(
                watchedBySource = it.watchedBySource, watchedByTag = it.watchedByTag, watchedTagFloors = it.watchedTagFloors,
                searchesByTag = it.searchesByTag,
            )
        }
        .distinctUntilChanged()
    val byProfile = settings.observeSettings().map { it.activeProfileId }.distinctUntilChanged().flatMapLatest { profileId ->
        val savedTags = settings.observeSettings()
            .map { it.favoriteTagsByProfile[profileId].orEmpty().flatMap { entry -> entry.tags } }.distinctUntilChanged()
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

private fun retryDelayMs(attempt: Long): Long = RETRY_BASE_MS shl attempt.coerceAtMost(RETRY_MAX_DOUBLINGS).toInt()

private const val RETRY_BASE_MS = 1_000L
private const val RETRY_MAX_DOUBLINGS = 6L

fun tagAffinities(post: Post, inputs: TagAffinityInputs, feedShare: Map<String, Double> = emptyMap()): List<TagAffinity> =
    post.canonicalTags.ifEmpty { post.rawTags }
        .filter(String::isNotBlank)
        .distinctBy(::normalizeMatchToken)
        .map { tag -> inputs.affinity(post.id.source, tag, feedShare) }
        .sortedByDescending(TagAffinity::score)

fun TagAffinityInputs.affinity(source: SourceKey, tag: String, feedShare: Map<String, Double> = emptyMap()): TagAffinity {
    val token = normalizeMatchToken(tag)
    val interest = interest[token] ?: 0.0
    val watched = watchedShare(source, token)
    val likedShare = if (likes >= MIN_LIKES_FOR_SHARE) (likedCounts[token] ?: 0).toDouble() / likes else 0.0
    val chosen = token in explicit
    val common = if (watched != null) watched >= UNIVERSAL_WATCHED_SHARE else likedShare > UNIVERSAL_LIKED_SHARE && !chosen
    val universal = common || (feedShare[token] ?: 0.0) >= UNIVERSAL_FEED_SHARE
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

fun TagAffinityInputs.pictureAffinity(source: SourceKey, booruToken: String, sourceToken: String): TagAffinity {
    val kept = pictures.keptByTag[booruToken] ?: 0.0
    val posts = pictures.postsByTag[booruToken] ?: 0
    val watched = watchedShare(source, normalizeMatchToken(sourceToken))
    val watchedAtMost = watchedShareAtMost(source, normalizeMatchToken(sourceToken)) ?: 0.0
    val baseRate = pictures.baseRates[booruToken] ?: 0.0
    val universal = maxOf(watched ?: 0.0, baseRate) >= UNIVERSAL_WATCHED_SHARE || posts > UNIVERSAL_LIKED_SHARE * pictures.kept
    val floor = LIFT_PSEUDO_COUNT / maxOf(watchedBySource[source] ?: 0L, MIN_WATCHED_FOR_SHARE)
    val lift = (kept + LIFT_PSEUDO_COUNT) / (pictures.kept + 1.0) / maxOf(watchedAtMost, baseRate, floor)
    val proven = !universal && !isImageTextTag(booruToken) && !isFileTag(booruToken) && pictures.kept >= MIN_KEPT_FOR_LIFT && posts >= MIN_KEPT_FOR_TAG &&
        lift >= if (watched != null) MIN_INFORMATIVE_LIFT else COLD_PICTURE_LIFT
    return TagAffinity(sourceToken, interest = kept, score = ln(lift) * ln(1.0 + kept), universal, informative = proven, proven = proven)
}

private fun TagAffinityInputs.historyScore(
    source: SourceKey,
    kept: Int,
    lift: Double,
    chosen: Boolean,
    proven: Boolean,
    watched: Double,
): Pair<Double, Boolean> {
    val score = ln(lift) * ln(1.0 + kept) + if (chosen) EXPLICIT_BONUS else 0.0
    if (proven) return score to true
    val tooRareToJudge = kept < MIN_KEPT_FOR_TAG && watched * (keptBySource[source] ?: 0) < MIN_KEPT_FOR_TAG
    return if (tooRareToJudge) -watched to true else score to false
}

fun feedTagShare(posts: List<Post>): Map<String, Double> {
    if (posts.size < MIN_FEED_POSTS) return emptyMap()
    return posts.map { it.canonicalTags.ifEmpty { it.rawTags } }.tokenCounts().mapValues { (_, count) -> count.toDouble() / posts.size }
}

private fun List<List<String>>.tokenCounts(): Map<String, Int> =
    flatMap { tags -> tags.map(::normalizeMatchToken).filter(String::isNotBlank).distinct() }.groupingBy { it }.eachCount()

const val EXPLICIT_WEIGHT = 3.0

private const val EXPLICIT_BONUS = 3.0
private const val MIN_SEARCHES_FOR_INTEREST = 2

private const val UNKNOWN_TAG_INTEREST = 0.5
private const val MIN_WATCHED_FOR_SHARE = 20L
private const val UNIVERSAL_WATCHED_SHARE = 0.5
private const val MIN_LIKES_FOR_SHARE = 4
private const val UNIVERSAL_LIKED_SHARE = 0.85
private const val MIN_FEED_POSTS = 10

fun isImageTextTag(token: String): Boolean = token in IMAGE_TEXT_TAGS || IMAGE_TEXT_ENDINGS.any(token::endsWith)

private val IMAGE_TEXT_TAGS = setOf("artist name", "character name", "copyright name", "signature", "watermark", "web address", "dated")
private val IMAGE_TEXT_ENDINGS = listOf(" username", " logo", " watermark")

fun isFileTag(token: String): Boolean =
    token in FILE_TAGS || FILE_TAG_PREFIXES.any(token::startsWith) || FILE_TAG_ENDINGS.any(token::endsWith) ||
        (token.startsWith("bad ") && token.endsWith(" id"))

private val FILE_TAGS = setOf(
    "2d", "3d", "3dcg", "3d animation", "3d model", "cgi", "render", "source filmmaker", "sfm", "mmd", "blender", "koikatsu",
    "animated", "animation", "gif", "webm", "mp4", "video", "loop", "looping animation", "ugoira", "うごイラ", "60fps",
    "sound", "audio", "has audio", "no audio", "no sound", "sound effects", "voice acted", "voice acting",
    "lowres", "highres", "absurdres", "incredibly absurdres", "4k", "huge filesize", "tagme", "commentary", "translated",
    "partially translated", "md5 mismatch", "duplicate",
)
private val FILE_TAG_PREFIXES = listOf("animated ", "audible ", "longer than ", "shorter than ")
private val FILE_TAG_ENDINGS = listOf(" (medium)", " (software)", " (artwork)", " commentary", " request")
private const val MIN_KEPT_FOR_LIFT = 20
private const val MIN_KEPT_FOR_TAG = 2
private const val MIN_INFORMATIVE_LIFT = 2.0

private const val COLD_PICTURE_LIFT = 4.0

private const val LIFT_PSEUDO_COUNT = 0.5
private const val UNIVERSAL_FEED_SHARE = 0.9
