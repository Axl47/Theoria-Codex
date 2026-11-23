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

/**
 * One lane of a Mix: its feed's posts not dealt yet, and where the feed continues. [label] is what its chip and cards say.
 * While vetting runs, a page's sparsely tagged posts wait in [unvetted] until their pictures are tagged and only then
 * join [buffer], the posts the deal takes from, so a post a hidden tag catches by its picture is never dealt. The rest
 * join [buffer] at once.
 */
internal class MixLane(val lane: ForYouLane, label: String = lane.label) {
    val origin = DeckOrigin.Lane(lane.id, label)
    var buffer: List<Post> = emptyList()
        private set
    var unvetted: List<Post> = emptyList()
        private set

    private var next: FeedCursor? = lane.cursor

    /**
     * Where the lane's next page starts; null once its feed has ended, or while its last [EMPTY_PAGE_LIMIT] pages gave the
     * deck nothing, so a feed that keeps answering with nothing the deck can show is not paged forever.
     */
    val cursor: FeedCursor? get() = next.takeIf { emptyPages < EMPTY_PAGE_LIMIT }
    var loading = false

    /** Whether the lane's first request has answered, whatever the answer. */
    var opened = false
        private set

    /** The lane's last request as its source reported it; a failed one keeps its page for a retry. */
    var status: SourceRunStatus? = null
        private set
    /** Pages in a row that gave the deck nothing; a page whose posts all wait for vetting counts until one passes. */
    private var emptyPages = 0

    /** Posts vetted from this lane, so lanes take turns at vetting while their buffers are level. */
    var vets = 0
        private set

    val failed: Boolean get() = status?.state == SourceRunState.FAILED
    val canPage: Boolean get() = cursor != null && !failed && !loading

    /**
     * Folds in the page asked for at [asked]; [fresh] are its posts the deck may show and has not been given. Those that
     * [needLook] wait in [unvetted]; the rest may be dealt at once.
     */
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

    /** Returns [posts] to the front of the lane; those that [needLook] are vetted again first. */
    fun putBack(posts: List<Post>, needLook: (Post) -> Boolean) {
        val (unchecked, clean) = posts.partition(needLook)
        unvetted = unchecked + unvetted
        buffer = clean + buffer
    }

    fun keep(shown: (Post) -> Boolean) {
        buffer = buffer.filter(shown)
        unvetted = unvetted.filter(shown)
    }

    /** [post]'s picture was tagged: it joins [buffer] when [shows]. Nothing happens if it stopped waiting meanwhile. */
    fun vetted(post: Post, shows: Boolean) {
        if (unvetted.none { it.id == post.id }) return
        unvetted = unvetted.filterNot { it.id == post.id }
        vets++
        if (shows) {
            buffer = buffer + post
            emptyPages = 0
        }
    }

    /** Sends what in the buffer [needLook]s back to be vetted, as when the first hidden tag arrives. */
    fun revet(needLook: (Post) -> Boolean) {
        val (unchecked, clean) = buffer.partition(needLook)
        unvetted = unchecked + unvetted
        buffer = clean
    }

    /** Posts the lane holds, vetted or not. */
    val waiting: Int get() = buffer.size + unvetted.size

    private companion object {
        const val EMPTY_PAGE_LIMIT = 3
    }
}

/**
 * One Mix: its lanes with what they have not dealt yet, this session's steering, and the cards dealt so far
 * ([released], in the order the deck and the grid show them). [only] is the lane an interest chip picked. It runs no
 * requests and is not thread-safe: its owner loads the lanes and uses it on one thread.
 */
internal class ForYouMix(lanes: List<ForYouLane>, private val copiesOf: (Post) -> Set<PostId> = { setOf(it.id) }) {
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

    /** How many cards the last deal came up short by; null before any deal, when a whole deal is owed. */
    private var short: Int? = null

    /** A deal came up short, or none has run yet, so the next cards to arrive are dealt at once. */
    val wanted: Boolean get() = short != 0

    /** How many cards to deal to make up for a short deal, or [whole] before any. */
    fun owed(whole: Int): Int = short ?: whole
    private val dealt = mutableSetOf<PostId>()

    /** A lane has answered its first request, so there may be cards to deal; lanes that answer later join in. */
    val ready: Boolean get() = lanes.any { it.opened }

    /** Some lane has not answered its first request yet. */
    val opening: Boolean get() = lanes.any { !it.opened }
    private val running: List<MixLane> get() = lanes.filter { only == null || it.origin.laneId == only }

    fun isDealt(id: PostId): Boolean = id in dealt

    /** Whether a deal would pass [post] over: it, or a copy of it, was [seen] or dealt already. */
    fun passesOver(post: Post, seen: Set<PostId>): Boolean =
        post.id in seen || post.id in dealt || copiesOf(post).any { it in seen || it in dealt }

    /** Deals up to [size] cards nobody has [seen], once a lane has opened; a copy of a seen or dealt post counts as seen. */
    fun deal(size: Int, seen: Set<PostId>) {
        // Before any lane answers nothing is owed but the whole first deal, whatever size the first answer finds.
        if (!ready) return
        val batch = nextBatch(lanes.map { DeckLane(it.origin, it.buffer) }, steering, seen + dealt, size, only, copiesOf)
        steering = batch.steering
        lanes.forEach { lane -> batch.taken[lane.origin.laneId]?.let(lane::drop) }
        batch.cards.forEach { card ->
            origins[card.post.id] = card.origin
            dealt += card.post.id
        }
        released = released + batch.cards.map(DeckCard::post)
        short = size - batch.cards.size
    }

    /**
     * Starts the dealt list over, as a chip change does: the cards nobody has [decided] go back to the front of the
     * lanes (or the pending cards) they came from, so the next deal can give them again. Lane cards that [needLook] are
     * vetted again first, since a hidden tag added after they were dealt may catch them by their pictures.
     */
    fun takeBack(decided: Set<PostId>, needLook: (Post) -> Boolean) {
        val back = released.filterNot { it.id in decided }.groupBy { (origins[it.id] as? DeckOrigin.Lane)?.laneId }
        lanes.forEach { lane -> back[lane.origin.laneId]?.let { lane.putBack(it, needLook) } }
        val asked = back[null].orEmpty().mapNotNull { post -> origins[post.id]?.let { DeckCard(post, it) } }
        steering = steering.copy(pending = asked + steering.pending)
        released = emptyList()
        dealt.clear()
        short = null
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
     * Adds the lanes of [plan] this Mix does not have, at the end. One whose label another lane has, added with it or
     * already here, gains its source. Returns whether any was added.
     */
    fun addLanes(plan: List<ForYouLane>): Boolean {
        val known = all.mapTo(mutableSetOf()) { it.lane.id }
        val fresh = plan.filter { known.add(it.id) }
        val every = all.map(MixLane::lane) + fresh
        val added = fresh.map { lane -> MixLane(lane, lane.labelAmong(every)) }
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
    fun lanesToRefill(): List<MixLane> = running.filter { it.canPage && it.waiting < LOW_WATER }

    /**
     * The next post to vet: from the running lane with the fewest vetted posts, while it has fewer than [lookAhead], or
     * whatever its count once a deal came up short. Lanes with as many take turns, since a short deal takes each card as
     * it is vetted and leaves every buffer empty. Null when nothing needs vetting yet.
     */
    fun nextToVet(lookAhead: Int): Pair<MixLane, Post>? =
        running.filter { it.unvetted.isNotEmpty() && (wanted || it.buffer.size < lookAhead) }
            .minWithOrNull(compareBy({ it.buffer.size }, { it.vets }))?.let { it to it.unvetted.first() }

    /** Sends what every lane's buffer [needLook]s back to be vetted. */
    fun revet(needLook: (Post) -> Boolean) = all.forEach { it.revet(needLook) }

    /** The failed lanes of [source], or of every source for null. */
    fun failedLanes(source: SourceKey? = null): List<MixLane> = lanes.filter { it.failed && (source == null || it.lane.source == source) }

    /** One failure per source, for the status row. */
    fun failures(): List<SourceRunStatus> = lanes.mapNotNull { lane -> lane.status.takeIf { lane.failed } }.distinctBy { it.source }

    /** The Mix as the deck and the grid read it. */
    fun snapshot(): TriageFeedSnapshot {
        val running = running
        val loading = running.any { it.loading }
        val buffered = steering.pending.isNotEmpty() || running.any { it.buffer.isNotEmpty() }
        val vetting = running.any { it.unvetted.isNotEmpty() }
        return TriageFeedSnapshot(
            posts = released,
            canLoadMore = buffered || vetting || running.any { it.cursor != null },
            // Before any lane has answered, the first requests are out; with no lane left to ask, nothing is loading.
            loadingMore = if (ready) wanted && (loading || vetting) else running.any { !it.opened },
            // Lanes that still give cards keep the deck going; a failed one waits in the status row.
            pageFailed = ready && wanted && !loading && !buffered && !vetting && running.any { it.failed },
        )
    }

    private companion object {
        /** A lane asks for its next page once it holds fewer posts, vetted or not, than this. */
        const val LOW_WATER = 6
    }
}

/** This lane's label, with its source's name when another of [lanes] reads the same: "fog · Pixiv". */
private fun ForYouLane.labelAmong(lanes: List<ForYouLane>): String =
    if (lanes.count { it.label == label } > 1) "$label · ${source.displayName()}" else label
