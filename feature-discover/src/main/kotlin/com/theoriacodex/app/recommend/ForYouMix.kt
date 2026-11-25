package com.theoriacodex.app.recommend

import com.theoriacodex.app.feed.FeedCursor
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.triage.TriageFeedSnapshot
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.orchestration.UnifiedSearchResult

internal class MixLane(val lane: ForYouLane, label: String = lane.label) {
    val origin = DeckOrigin.Lane(lane.id, label)
    var buffer: List<Post> = emptyList()
        private set
    var unvetted: List<Post> = emptyList()
        private set

    private var next: FeedCursor? = lane.cursor

    val cursor: FeedCursor? get() = next.takeIf { emptyPages < EMPTY_PAGE_LIMIT }
    var loading = false

    var opened = false
        private set

    var status: SourceRunStatus? = null
        private set
    private var emptyPages = 0

    var vets = 0
        private set

    val failed: Boolean get() = status?.state == SourceRunState.FAILED
    val canPage: Boolean get() = cursor != null && !failed && !loading

    fun accept(asked: FeedCursor, page: UnifiedSearchResult, fresh: List<Post>, needLook: (Post) -> Boolean = { false }) {
        val source = lane.source
        opened = true
        status = page.statuses.firstOrNull { it.source == source }
        if (failed) return
        val (unchecked, clean) = fresh.partition(needLook)
        unvetted = unvetted + unchecked
        buffer = buffer + clean
        emptyPages = if (clean.isEmpty()) emptyPages + 1 else 0
        next = asked.accept(page, setOf(source)).takeIf { source in it.pageTokens }
    }

    fun fail(message: String?) {
        opened = true
        status = SourceRunStatus(lane.source, SourceRunState.FAILED, errorMessage = message)
    }

    fun clearFailure() {
        status = null
    }

    fun drop(count: Int) {
        buffer = buffer.drop(count)
    }

    fun keep(shown: (Post) -> Boolean) {
        buffer = buffer.filter(shown)
        unvetted = unvetted.filter(shown)
    }

    fun vetted(post: Post, shows: Boolean) {
        if (unvetted.none { it.id == post.id }) return
        unvetted = unvetted.filterNot { it.id == post.id }
        vets++
        if (shows) {
            buffer = buffer + post
            emptyPages = 0
        }
    }

    fun revet(needLook: (Post) -> Boolean) {
        val (unchecked, clean) = buffer.partition(needLook)
        unvetted = unchecked + unvetted
        buffer = clean
    }

    val waiting: Int get() = buffer.size + unvetted.size

    private companion object {
        const val EMPTY_PAGE_LIMIT = 3
    }
}

internal class ForYouMix(
    lanes: List<ForYouLane>,
    private val copiesOf: (Post) -> Set<PostId> = { setOf(it.id) },
    leading: List<Post> = emptyList(),
) {
    private var all = lanes.map { lane -> MixLane(lane, lane.labelAmong(lanes)) }

    var lanes: List<MixLane> = all
        private set
    var steering = DeckSteering()
    var released: List<Post> = leading
        private set

    val origins = mutableMapOf<PostId, DeckOrigin>()

    private var short: Int? = null

    val wanted: Boolean get() = short != 0

    fun owed(whole: Int): Int = short ?: whole
    private val dealt = leading.mapTo(mutableSetOf(), Post::id)
    private val leadCount = leading.size

    fun freshFirst(): List<Post> = released.drop(leadCount) + released.take(leadCount)

    val ready: Boolean get() = lanes.any { it.opened }

    val opening: Boolean get() = lanes.any { !it.opened }

    fun isDealt(id: PostId): Boolean = id in dealt

    fun passesOver(post: Post, seen: (PostId) -> Boolean): Boolean =
        seen(post.id) || post.id in dealt || copiesOf(post).any { seen(it) || it in dealt }

    fun deal(size: Int, seen: (PostId) -> Boolean) {
        if (!ready) return
        val batch = nextBatch(lanes.map { DeckLane(it.origin, it.buffer) }, steering, { seen(it) || it in dealt }, size, copiesOf)
        steering = batch.steering
        lanes.forEach { lane -> batch.taken[lane.origin.laneId]?.let(lane::drop) }
        batch.cards.forEach { card ->
            origins[card.post.id] = card.origin
            dealt += card.post.id
        }
        released = released + batch.cards.map(DeckCard::post)
        short = size - batch.cards.size
    }

    fun hideLanes(hidden: (ForYouLane) -> Boolean) {
        val shown = all.filterNot { hidden(it.lane) }
        if (shown == lanes) return
        lanes = shown
        steering = steering.keeping(shown.mapTo(mutableSetOf()) { it.origin.laneId })
    }

    fun addLanes(plan: List<ForYouLane>): Boolean {
        val known = all.mapTo(mutableSetOf()) { it.lane.id }
        val fresh = plan.filter { known.add(it.id) }
        val every = all.map(MixLane::lane) + fresh
        val added = fresh.map { lane -> MixLane(lane, lane.labelAmong(every)) }
        all = all + added
        lanes = lanes + added
        return added.isNotEmpty()
    }

    fun keepWaiting(shown: (Post) -> Boolean) {
        lanes.forEach { it.keep(shown) }
        steering = steering.copy(pending = steering.pending.filter { shown(it.post) })
    }

    fun lanesToRefill(): List<MixLane> = lanes.filter { it.canPage && it.waiting < LOW_WATER }

    fun nextToVet(lookAhead: Int): Pair<MixLane, Post>? =
        lanes.filter { it.unvetted.isNotEmpty() && (wanted || it.buffer.size < lookAhead) }
            .minWithOrNull(compareBy({ it.buffer.size }, { it.vets }))?.let { it to it.unvetted.first() }

    fun revet(needLook: (Post) -> Boolean) = all.forEach { it.revet(needLook) }

    fun failedLanes(source: SourceKey? = null): List<MixLane> = lanes.filter { it.failed && (source == null || it.lane.source == source) }

    fun failures(): List<SourceRunStatus> = lanes.mapNotNull { lane -> lane.status.takeIf { lane.failed } }.distinctBy { it.source }

    fun snapshot(): TriageFeedSnapshot {
        val loading = lanes.any { it.loading }
        val buffered = steering.pending.isNotEmpty() || lanes.any { it.buffer.isNotEmpty() }
        val vetting = lanes.any { it.unvetted.isNotEmpty() }
        return TriageFeedSnapshot(
            posts = released,
            canLoadMore = buffered || vetting || lanes.any { it.cursor != null },
            loadingMore = if (ready) wanted && (loading || vetting) else lanes.any { !it.opened },
            pageFailed = ready && wanted && !loading && !buffered && !vetting && lanes.any { it.failed },
        )
    }

    private companion object {
        const val LOW_WATER = 6
    }
}

private fun ForYouLane.labelAmong(lanes: List<ForYouLane>): String =
    if (lanes.count { it.label == label } > 1) "$label · ${source.displayName()}" else label
