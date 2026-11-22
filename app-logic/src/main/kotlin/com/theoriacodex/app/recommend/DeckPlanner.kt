package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

/** Why a card is in the For You deck. */
sealed interface DeckOrigin {
    /** One of the profile's interests, or a trending lane for a profile with no likes; [label] names it on the card. */
    data class Lane(val laneId: String, val label: String) : DeckOrigin

    /** A post like [of], which the user just liked or saved. */
    data class Similar(val of: PostId) : DeckOrigin

    /** Another post by the creator of [of], which the user just liked or saved. */
    data class Creator(val of: PostId, val name: String) : DeckOrigin
}

/** A post in the For You deck and what brought it there. */
data class DeckCard(val post: Post, val origin: DeckOrigin)

/** One lane's posts the deck has not shown yet, in the lane's own order. */
data class DeckLane(val origin: DeckOrigin.Lane, val posts: List<Post>)

/** What a swipe tells steering about the card's origin. */
enum class DeckSignal { Liked, Saved, Skipped, Hid }

/**
 * How this session's swipes bend the mix. It lives in memory only: a run of skips lowers a lane's share until a like or
 * save restores it, and [pending] holds the cards a like or save asked for. The swipes themselves are also logged per
 * profile (`ForYouVerdictRepository`) for a future taste model, but nothing reads that log back into steering yet.
 */
data class DeckSteering(
    val weights: Map<String, Double> = emptyMap(),
    val skipRuns: Map<String, Int> = emptyMap(),
    val pending: List<DeckCard> = emptyList(),
    /** Smooth weighted round-robin credit by lane, so a batch picks up where the last one stopped. */
    val credit: Map<String, Double> = emptyMap(),
) {
    fun weight(laneId: String): Double = weights[laneId] ?: 1.0

    /**
     * Steering after the user decided a card from [origin]. Every [SKIP_RUN]th skip or hide in a row halves a lane's
     * weight, down to [MIN_WEIGHT]; a like or save ends the run and restores the lane. Cards that did not come from a
     * lane steer nothing.
     */
    fun after(signal: DeckSignal, origin: DeckOrigin): DeckSteering {
        val lane = (origin as? DeckOrigin.Lane)?.laneId ?: return this
        return when (signal) {
            DeckSignal.Liked, DeckSignal.Saved -> copy(weights = weights - lane, skipRuns = skipRuns - lane)
            DeckSignal.Skipped, DeckSignal.Hid -> {
                val run = (skipRuns[lane] ?: 0) + 1
                if (run < SKIP_RUN) copy(skipRuns = skipRuns + (lane to run))
                else copy(weights = weights + (lane to (weight(lane) / 2).coerceAtLeast(MIN_WEIGHT)), skipRuns = skipRuns - lane)
            }
        }
    }

    /** Queues [cards], the answer to a like or save, ahead of the lanes' next picks. */
    fun inject(cards: List<DeckCard>): DeckSteering = copy(pending = pending + cards)

    /** Steering as it was before the decision on [post]: [before] with any cards [post] has asked for since dropped. */
    fun undone(before: DeckSteering, post: PostId): DeckSteering =
        before.copy(pending = pending.filterNot { it.origin.causedBy(post) }, credit = credit)

    /** Steering without lanes that no longer exist, such as an interest whose tag was just hidden. */
    fun keeping(laneIds: Set<String>): DeckSteering = copy(
        weights = weights.filterKeys(laneIds::contains),
        skipRuns = skipRuns.filterKeys(laneIds::contains),
        credit = credit.filterKeys(laneIds::contains),
    )

    companion object {
        /** Skips or hides in a row that halve a lane's weight. */
        const val SKIP_RUN = 3
        const val MIN_WEIGHT = 0.25
    }
}

private fun DeckOrigin.causedBy(post: PostId): Boolean = when (this) {
    is DeckOrigin.Lane -> false
    is DeckOrigin.Similar -> of == post
    is DeckOrigin.Creator -> of == post
}

/** The next cards for the deck, the steering to plan the following batch with, and how many posts each lane gave. */
data class DeckBatch(val cards: List<DeckCard>, val steering: DeckSteering, val taken: Map<String, Int>)

/**
 * Up to [size] cards nobody has [seen]: the pending cards a like or save asked for first, then the lanes in turn, each
 * as often as its weight says. It is deterministic, and a lane running out simply gives its turns to the others.
 * [only] names the one lane an interest chip picked; pending cards still come first. A lane's seen posts count as
 * [DeckBatch.taken] too, so the caller drops them from its buffer. [copiesOf] names the posts that hold the same file
 * or picture as a post, itself included: a post counts as seen when any copy is, and dealing it uses all of them, so
 * one picture on several sites is dealt once.
 */
fun nextBatch(
    lanes: List<DeckLane>,
    steering: DeckSteering,
    seen: Set<PostId>,
    size: Int,
    only: String? = null,
    copiesOf: (Post) -> Set<PostId> = { setOf(it.id) },
): DeckBatch {
    val used = seen.toMutableSet()
    fun isUsed(post: Post) = post.id in used || copiesOf(post).any(used::contains)
    fun use(post: Post) {
        used += post.id
        used += copiesOf(post)
    }
    val cards = mutableListOf<DeckCard>()
    val pending = steering.pending.toMutableList()
    while (cards.size < size && pending.isNotEmpty()) {
        val card = pending.removeAt(0)
        if (!isUsed(card.post)) {
            use(card.post)
            cards += card
        }
    }
    val running = lanes.filter { only == null || it.origin.laneId == only }
    val cursor = running.associateTo(mutableMapOf()) { it.origin.laneId to 0 }
    val credit = steering.credit.toMutableMap()
    while (cards.size < size) {
        // Skip what another lane or an injection already gave, so a lane's turn always yields a card.
        val ready = running.filter { lane ->
            var at = cursor.getValue(lane.origin.laneId)
            while (at < lane.posts.size && isUsed(lane.posts[at])) at++
            cursor[lane.origin.laneId] = at
            at < lane.posts.size
        }
        if (ready.isEmpty()) break
        ready.forEach { lane -> credit.merge(lane.origin.laneId, steering.weight(lane.origin.laneId), Double::plus) }
        val pick = ready.maxBy { credit.getValue(it.origin.laneId) }
        val id = pick.origin.laneId
        credit[id] = credit.getValue(id) - ready.sumOf { steering.weight(it.origin.laneId) }
        val post = pick.posts[cursor.getValue(id)]
        cursor[id] = cursor.getValue(id) + 1
        use(post)
        cards += DeckCard(post, pick.origin)
    }
    return DeckBatch(cards, steering.copy(pending = pending, credit = credit), cursor.filterValues { it > 0 })
}
