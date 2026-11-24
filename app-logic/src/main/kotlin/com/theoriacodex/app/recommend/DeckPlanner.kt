package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

sealed interface DeckOrigin {
    data class Lane(val laneId: String, val label: String) : DeckOrigin

    data class Similar(val of: PostId, val saved: Boolean) : DeckOrigin

    data class Creator(val of: PostId, val name: String) : DeckOrigin
}

data class DeckCard(val post: Post, val origin: DeckOrigin)

data class DeckLane(val origin: DeckOrigin.Lane, val posts: List<Post>)

enum class DeckSignal { Liked, Saved, Skipped, Hid }

data class DeckSteering(
    val weights: Map<String, Double> = emptyMap(),
    val skipRuns: Map<String, Int> = emptyMap(),
    val pending: List<DeckCard> = emptyList(),
    val credit: Map<String, Double> = emptyMap(),
) {
    fun weight(laneId: String): Double = weights[laneId] ?: 1.0

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

    fun inject(cards: List<DeckCard>): DeckSteering = copy(pending = pending + cards)

    fun undone(before: DeckSteering, post: PostId): DeckSteering =
        before.copy(pending = pending.filterNot { it.origin.causedBy(post) }, credit = credit)

    fun keeping(laneIds: Set<String>): DeckSteering = copy(
        weights = weights.filterKeys(laneIds::contains),
        skipRuns = skipRuns.filterKeys(laneIds::contains),
        credit = credit.filterKeys(laneIds::contains),
    )

    companion object {
        const val SKIP_RUN = 3
        const val MIN_WEIGHT = 0.25
    }
}

private fun DeckOrigin.causedBy(post: PostId): Boolean = when (this) {
    is DeckOrigin.Lane -> false
    is DeckOrigin.Similar -> of == post
    is DeckOrigin.Creator -> of == post
}

data class DeckBatch(val cards: List<DeckCard>, val steering: DeckSteering, val taken: Map<String, Int>)

fun nextBatch(
    lanes: List<DeckLane>,
    steering: DeckSteering,
    seen: (PostId) -> Boolean,
    size: Int,
    only: String? = null,
    copiesOf: (Post) -> Set<PostId> = { setOf(it.id) },
): DeckBatch {
    val used = HashSet<PostId>()
    fun isUsed(id: PostId) = seen(id) || id in used
    fun isUsed(post: Post) = isUsed(post.id) || copiesOf(post).any(::isUsed)
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
