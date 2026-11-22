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

/** One lane of a Mix: its feed's posts not dealt yet, and where the feed continues. [label] is what its chip and cards say. */
internal class MixLane(val lane: ForYouLane, label: String = lane.label) {
    val origin = DeckOrigin.Lane(lane.id, label)
    var buffer: List<Post> = emptyList()
        private set

    /** Where the lane's next page starts; null once its feed has ended. */
    var cursor: FeedCursor? = lane.cursor
        private set
    var loading = false

    /** Whether the lane's first request has answered, whatever the answer. */
    var opened = false
        private set

    /** The lane's last request as its source reported it; a failed one keeps its page for a retry. */
    var status: SourceRunStatus? = null
        private set
    private var emptyPages = 0

    val failed: Boolean get() = status?.state == SourceRunState.FAILED
    val canPage: Boolean get() = cursor != null && !failed && !loading

    /** Folds in the page asked for at [asked]; [fresh] are its posts the deck may show and has not been given. */
    fun accept(asked: FeedCursor, page: UnifiedSearchResult, fresh: List<Post>) {
        val source = lane.source
        opened = true
        status = page.statuses.firstOrNull { it.source == source }
        if (failed) return
        buffer = buffer + fresh
        emptyPages = if (fresh.isEmpty()) emptyPages + 1 else 0
        // A feed that keeps answering with nothing the deck can show is treated as ended, so it is not paged forever.
        cursor = asked.accept(page, setOf(source)).takeIf { source in it.pageTokens && emptyPages < EMPTY_PAGE_LIMIT }
    }

    /** The request threw before any source answered. */
    fun fail(message: String?) {
        opened = true
        status = SourceRunStatus(lane.source, SourceRunState.FAILED, errorMessage = message)
    }

    /** Forgets the failure, so the lane may ask for the same page again. */
    fun clearFailure() {
        status = null
    }

    fun drop(count: Int) {
        buffer = buffer.drop(count)
    }

    fun putBack(posts: List<Post>) {
        buffer = posts + buffer
    }

    fun keep(shown: (Post) -> Boolean) {
        buffer = buffer.filter(shown)
    }

    private companion object {
        const val EMPTY_PAGE_LIMIT = 3
    }
}

/**
 * One Mix: its lanes with what they have not dealt yet, this session's steering, and the cards dealt so far
 * ([released], in the order the deck and the grid show them). [only] is the lane an interest chip picked. It runs no
 * requests and is not thread-safe: its owner loads the lanes and uses it on one thread.
 */
internal class ForYouMix(lanes: List<ForYouLane>) {
    /** Every lane of the plan, in order, with the ones hidden tags hide; grows when a hide that kept lanes out goes. */
    private var all = lanes.map { lane -> MixLane(lane, lane.labelAmong(lanes)) }

    /** The lanes the profile's hidden tags do not hide. */
    var lanes: List<MixLane> = all
        private set
    var steering = DeckSteering()
    var only: String? = null
    var released: List<Post> = emptyList()
        private set

    /** What brought each dealt card, by post. */
    val origins = mutableMapOf<PostId, DeckOrigin>()

    /** A deal came up short, or none has run yet, so the next lane page deals again. */
    var wanted = true
        private set
    private val dealt = mutableSetOf<PostId>()

    /** A lane has answered its first request, so there may be cards to deal; lanes that answer later join in. */
    val ready: Boolean get() = lanes.any { it.opened }

    /** Some lane has not answered its first request yet. */
    val opening: Boolean get() = lanes.any { !it.opened }
    private val running: List<MixLane> get() = lanes.filter { only == null || it.origin.laneId == only }

    fun isDealt(id: PostId): Boolean = id in dealt

    /** Deals up to [size] cards nobody has [seen], once a lane has opened. */
    fun deal(size: Int, seen: Set<PostId>) {
        if (!ready) {
            wanted = true
            return
        }
        val batch = nextBatch(lanes.map { DeckLane(it.origin, it.buffer) }, steering, seen + dealt, size, only)
        steering = batch.steering
        lanes.forEach { lane -> batch.taken[lane.origin.laneId]?.let(lane::drop) }
        batch.cards.forEach { card ->
            origins[card.post.id] = card.origin
            dealt += card.post.id
        }
        released = released + batch.cards.map(DeckCard::post)
        wanted = batch.cards.size < size
    }

    /**
     * Starts the dealt list over, as a chip change does: the cards nobody has [decided] go back to the front of the
     * lanes (or the pending cards) they came from, so the next deal can give them again.
     */
    fun takeBack(decided: Set<PostId>) {
        val back = released.filterNot { it.id in decided }.groupBy { (origins[it.id] as? DeckOrigin.Lane)?.laneId }
        lanes.forEach { lane -> back[lane.origin.laneId]?.let(lane::putBack) }
        val asked = back[null].orEmpty().mapNotNull { post -> origins[post.id]?.let { DeckCard(post, it) } }
        steering = steering.copy(pending = asked + steering.pending)
        released = emptyList()
        dealt.clear()
        wanted = true
    }

    /**
     * Leaves out the lanes [hidden] names, with their steering, and brings back the ones it no longer names, as Undo
     * of a hide does. A lane left out keeps what it had not dealt.
     */
    fun hideLanes(hidden: (ForYouLane) -> Boolean) {
        val shown = all.filterNot { hidden(it.lane) }
        if (shown == lanes) return
        lanes = shown
        steering = steering.keeping(shown.mapTo(mutableSetOf()) { it.origin.laneId })
        if (shown.none { it.origin.laneId == only }) only = null
    }

    /**
     * Adds the lanes of [plan] this Mix does not have, at the end. A label another lane already uses gains its source.
     * Returns whether any was added.
     */
    fun addLanes(plan: List<ForYouLane>): Boolean {
        val known = all.mapTo(mutableSetOf()) { it.lane.id }
        val added = plan.filter { known.add(it.id) }.map { lane -> MixLane(lane, lane.labelAmong(all.map(MixLane::lane) + lane)) }
        all = all + added
        lanes = lanes + added
        return added.isNotEmpty()
    }

    /** Drops the posts waiting to be dealt that the deck may no longer show. */
    fun keepWaiting(shown: (Post) -> Boolean) {
        lanes.forEach { it.keep(shown) }
        steering = steering.copy(pending = steering.pending.filter { shown(it.post) })
    }

    /** The lanes to ask for a page: those the chip runs that are close to running out. */
    fun lanesToRefill(): List<MixLane> = running.filter { it.canPage && it.buffer.size < LOW_WATER }

    /** The failed lanes of [source], or of every source for null. */
    fun failedLanes(source: SourceKey? = null): List<MixLane> = lanes.filter { it.failed && (source == null || it.lane.source == source) }

    /** One failure per source, for the status row. */
    fun failures(): List<SourceRunStatus> = lanes.mapNotNull { lane -> lane.status.takeIf { lane.failed } }.distinctBy { it.source }

    /** The Mix as the deck and the grid read it. */
    fun snapshot(): TriageFeedSnapshot {
        val running = running
        val loading = running.any { it.loading }
        val buffered = steering.pending.isNotEmpty() || running.any { it.buffer.isNotEmpty() }
        return TriageFeedSnapshot(
            posts = released,
            canLoadMore = buffered || running.any { it.cursor != null },
            loadingMore = !ready || (wanted && loading),
            // Lanes that still give cards keep the deck going; a failed one waits in the status row.
            pageFailed = ready && wanted && !loading && !buffered && running.any { it.failed },
        )
    }

    private companion object {
        /** A lane asks for its next page once it holds fewer posts than this. */
        const val LOW_WATER = 6
    }
}

/** This lane's label, with its source's name when another of [lanes] reads the same: "fog · Pixiv". */
private fun ForYouLane.labelAmong(lanes: List<ForYouLane>): String =
    if (lanes.count { it.label == label } > 1) "$label · ${source.displayName()}" else label
