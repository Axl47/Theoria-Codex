package com.theoriacodex.app.recommend

import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.app.codex.QuickSaveRecord
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.app.feed.FeedCursor
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.trails.TrailPage
import com.theoriacodex.app.trails.TrailPager
import com.theoriacodex.app.trails.TrailSpec
import com.theoriacodex.app.triage.HiddenTag
import com.theoriacodex.app.triage.TriageActions
import com.theoriacodex.app.triage.TriageReversal
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.ForYouVerdictRepository
import com.theoriacodex.data.repository.InMemoryForYouVerdictRepository
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.orchestration.UnifiedSearchResult
import com.theoriacodex.domain.vision.PictureLook
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import com.theoriacodex.domain.vision.PictureTags
import com.theoriacodex.domain.vision.PredictedTag
import com.theoriacodex.domain.vision.PredictedTagIndex

internal val DECK_SOURCE = SourceKey.GELBOORU

internal const val RICH = "rich"

internal val VOCABULARY = List(10) { "word $it" }

internal fun lanePost(tag: String, page: Int, index: Int): Post = testPost(
    source = DECK_SOURCE,
    sourcePostId = "$tag-$page-$index",
    canonicalTags = if (tag == RICH) listOf(tag) + VOCABULARY else listOf(tag),
    creatorProfile = CreatorProfile(DECK_SOURCE, "artist", uploadsQuery = "user:artist"),
)

internal fun List<Post>.lanes(): List<String> = map { it.id.sourcePostId.substringBefore('-') }

internal fun laneId(tag: String): String = ForYouLane(DECK_SOURCE, listOf(tag), FeedCursor(Query.newest(DECK_SOURCE))).id

internal fun hiding(vararg tags: String): AppSettings = AppSettings().let { settings ->
    settings.copy(forYouBlacklistByProfile = mapOf(settings.activeProfileId to tags.map { ForYouBlacklistEntry(DECK_SOURCE, listOf(it)) }))
}

internal class FakeForYouEngine(var tags: List<String>, private val pages: Int = 4, private val pageSize: Int = PAGE) : ForYouRouteEngine {
    var sources = listOf(DECK_SOURCE)
    val failing = mutableSetOf<String>()
    var failLanes = false
    private val held = mutableMapOf<String, CompletableDeferred<Unit>>()

    var planGate: CompletableDeferred<Unit>? = null

    fun hold(vararg tags: String) = tags.forEach { held[it] = CompletableDeferred() }

    fun release(tag: String) {
        held.remove(tag)?.complete(Unit)
    }

    val requests = mutableListOf<String>()
    var builds = 0

    override fun availableSources(settings: AppSettings): List<SourceKey> = sources

    override suspend fun lanes(request: ForYouFeedRequest): List<ForYouLane> {
        builds++
        planGate?.await()
        check(!failLanes) { "down" }
        val hidden = request.settings.forYouBlacklistByProfile[request.settings.activeProfileId].orEmpty()
        return tags.filterNot { hidden.hidesSeed(DECK_SOURCE, listOf(it)) }.map { tag ->
            val query = forYouSourceQuery(DECK_SOURCE, listOf(tag), SortMode.NEWEST)
            ForYouLane(DECK_SOURCE, listOf(tag), FeedCursor(query, mapOf(DECK_SOURCE to query), pageTokens = mapOf(DECK_SOURCE to null)))
        }
    }

    override suspend fun loadPage(continuation: FeedCursor, sources: Set<SourceKey>): UnifiedSearchResult {
        val tag = continuation.query.includeTags.single()
        val page = continuation.pageTokens[DECK_SOURCE]?.toInt() ?: 0
        requests += "$tag:$page"
        held[tag]?.await()
        if (tag in failing) {
            return UnifiedSearchResult(emptyList(), emptyMap(), listOf(SourceRunStatus(DECK_SOURCE, SourceRunState.FAILED, errorMessage = "down")))
        }
        return UnifiedSearchResult(
            items = List(pageSize) { lanePost(tag, page, it) },
            nextPageTokens = mapOf(DECK_SOURCE to (page + 1).takeIf { it < pages }?.toString()),
            statuses = listOf(SourceRunStatus(DECK_SOURCE, SourceRunState.SUCCESS)),
        )
    }

    override suspend fun resolvePost(postId: PostId): Post? = null

    companion object {
        const val PAGE = 8
    }
}

internal class FakeTrails(
    var similar: List<Post>? = null,
    var creator: List<Post>? = null,
    var tag: List<Post>? = null,
) {
    val asked = mutableListOf<TrailRequest>()
    var gate: CompletableDeferred<Unit>? = null
    var pager: TrailPager? = null

    suspend fun open(request: TrailRequest, @Suppress("UNUSED_PARAMETER") animatedOnly: Boolean): TrailSpec? {
        asked += request
        gate?.await()
        return when (request) {
            is TrailRequest.Similar -> similar?.let { spec("Similar", null, it) }
            is TrailRequest.Creator -> creator?.let { spec(request.creator.displayName, null, it) }
            is TrailRequest.Tag -> tag?.let { spec(request.tag, forYouSourceQuery(request.source, listOf(request.tag), SortMode.NEWEST), it) }
        }
    }

    private fun spec(label: String, query: Query?, posts: List<Post>) = TrailSpec(label, query, pager ?: TrailPager { TrailPage(posts, canLoadMore = false) })
}

internal class FakeDeckActions : TriageActions {
    val liked = mutableListOf<PostId>()
    val unliked = mutableListOf<PostId>()
    val hidden = mutableListOf<Pair<SourceKey, String>>()
    var failLike = false
    override val hiddenTags: Flow<Set<HiddenTag>> = flowOf(emptySet())

    override suspend fun like(post: Post): TriageReversal? {
        check(!failLike)
        liked += post.id
        return { unliked += post.id }
    }

    override suspend fun hide(source: SourceKey, tag: String): TriageReversal? {
        hidden += source to tag
        return { hidden -= source to tag }
    }
}

internal class FakeDeckQuickSave : QuickSave {
    val saved = mutableListOf<PostId>()
    var fromForYou = false
    override val lastQuickSave: StateFlow<QuickSaveRecord?> = MutableStateFlow(null)

    override suspend fun quickSave(post: Post, codexId: String, query: Query?, fromForYou: Boolean): QuickSaveRecord {
        this.fromForYou = fromForYou
        saved += post.id
        return QuickSaveRecord(post, codexId, "Skies", inserted = 1, savedAtEpochMs = 0L)
    }

    override suspend fun saveToNew(post: Post, name: String, profileId: String, query: Query?, fromForYou: Boolean) =
        quickSave(post, "new:$name", query, fromForYou)

    override suspend fun undoQuickSave(record: QuickSaveRecord) {
        saved -= record.post.id
    }

    override fun suggestions(post: Post, query: Query?, limit: Int): Flow<SaveSuggestions> = flowOf(SaveSuggestions.Empty)
}

internal object NoRelatedPosts : RelatedPostsLoading {
    override fun supports(source: SourceKey): Boolean = false
    override suspend fun load(seed: PostId): List<Post> = emptyList()
}

internal fun deckWiring(
    scope: CoroutineScope,
    trails: FakeTrails = FakeTrails(),
    actions: FakeDeckActions = FakeDeckActions(),
    quickSave: FakeDeckQuickSave = FakeDeckQuickSave(),
    verdicts: ForYouVerdictRepository = InMemoryForYouVerdictRepository(),
    copiesOf: (Post) -> Set<PostId> = { setOf(it.id) },
    pictures: PictureTags = PictureTags.None,
    clock: () -> Long = AtomicLong()::incrementAndGet,
    reconnections: Flow<Unit> = emptyFlow(),
) = ForYouDeckWiring(
    actions, quickSave, trails::open, NoRelatedPosts, scope, reconnections, fetchContext = EmptyCoroutineContext, verdicts = verdicts,
    copiesOf = copiesOf, clock = clock, pictures = pictures,
)

internal class FakePictureTags(ready: Boolean = true, private val predicted: Map<String, List<PredictedTag>> = emptyMap()) : PictureTags {
    override val ready = MutableStateFlow(ready)
    override val library = MutableStateFlow(PredictedTagIndex(baseRates = VOCABULARY.associateWith { 0.01 }))
    private val seen = HashMap<PostId, List<PredictedTag>>()
    val asked = mutableListOf<String>()
    var held: CompletableDeferred<Unit>? = null
    var unseen: Set<String> = emptySet()
    var blind = false
    private var looking = 0
    var mostLooking = 0

    fun release() = held?.complete(Unit)

    override suspend fun look(post: Post): PictureLook {
        if (!ready.value || blind) return PictureLook.Blind
        looking += 1
        mostLooking = maxOf(mostLooking, looking)
        try {
            if (post.id.sourcePostId in unseen) return PictureLook.Unseen
            held?.await()
        } finally {
            looking -= 1
        }
        asked += post.id.sourcePostId
        val tags = predicted[post.id.sourcePostId].orEmpty()
        seen[post.id] = tags
        return PictureLook.Seen(tags)
    }

    override fun known(id: PostId): List<PredictedTag>? = seen[id]
}

internal fun seen(tag: String) = listOf(PredictedTag(tag, 0.9f, PredictedTag.Kind.GENERAL))

internal fun deckEnvironment(
    settings: AppSettings = AppSettings(),
    grid: Boolean = false,
    kept: Set<PostId> = emptySet(),
    creatorBrowsingSources: Set<SourceKey> = emptySet(),
) = ForYouEnvironment(settings, selectedSource = null, SortMode.NEWEST, animatedOnly = false, grid, kept, creatorBrowsingSources)
