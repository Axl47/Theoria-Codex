package com.theoriacodex.app.recommend

import com.theoriacodex.data.repository.ForYouVerdictKind
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class DeckPlannerTest {
    private val a = DeckOrigin.Lane("a", "a")
    private val b = DeckOrigin.Lane("b", "b")

    @Test fun `lanes take turns and each card names its lane (ADR 0070)`() {
        val batch = nextBatch(listOf(lane(a, "a1", "a2", "a3"), lane(b, "b1", "b2")), DeckSteering(), { false }, size = 4)

        assertEquals(listOf("a1" to a, "b1" to b, "a2" to a, "b2" to b), batch.cards.map { it.post.id.sourcePostId to it.origin })
        assertEquals(mapOf("a" to 2, "b" to 2), batch.taken)
    }

    @Test fun `a post two lanes hold is dealt once and a seen post never (ADR 0070)`() {
        val batch = nextBatch(listOf(lane(a, "x", "a2"), lane(b, "x", "b2")), DeckSteering(), setOf(id("a2"))::contains, size = 4)

        assertEquals(listOf("x", "b2"), batch.cards.map { it.post.id.sourcePostId })
        assertEquals("the caller drops the seen and duplicate posts too", mapOf("a" to 2, "b" to 2), batch.taken)
    }

    @Test fun `three skips in a row halve a lane until a like restores it (ADR 0070)`() {
        val skipped = (1..3).fold(DeckSteering()) { steering, _ -> steering.after(ForYouVerdictKind.SKIPPED, a) }
        assertEquals(0.5, skipped.weight("a"), 0.0)

        val lanes = listOf(lane(a, "a1", "a2", "a3", "a4"), lane(b, "b1", "b2", "b3", "b4"))
        assertEquals(listOf("b", "a", "b", "b", "a", "b"), nextBatch(lanes, skipped, { false }, 6).cards.map { (it.origin as DeckOrigin.Lane).laneId })

        assertEquals(1.0, skipped.after(ForYouVerdictKind.LIKED, a).weight("a"), 0.0)
    }

    @Test fun `a like between skips ends the run and weight never falls below the floor (ADR 0070)`() {
        val broken = DeckSteering().after(ForYouVerdictKind.SKIPPED, a).after(ForYouVerdictKind.SKIPPED, a).after(ForYouVerdictKind.SAVED, a)
            .after(ForYouVerdictKind.SKIPPED, a)
        assertEquals(1.0, broken.weight("a"), 0.0)

        val reduced = (1..3).fold(DeckSteering()) { steering, _ -> steering.after(ForYouVerdictKind.SKIPPED, a) }
        assertEquals(1.0, reduced.after(ForYouVerdictKind.SAVED, a).weight("a"), 0.0)

        val floor = (1..12).fold(DeckSteering()) { steering, _ -> steering.after(ForYouVerdictKind.HID, a) }
        assertEquals(0.25, floor.weight("a"), 0.0)
    }

    @Test fun `every third hide halves a lane (ADR 0070)`() {
        val weights = (1..6).runningFold(DeckSteering()) { steering, _ -> steering.after(ForYouVerdictKind.HID, a) }.map { it.weight("a") }

        assertEquals(listOf(1.0, 1.0, 1.0, 0.5, 0.5, 0.5, 0.25), weights)
    }

    @Test fun `injected cards come first and undo drops the ones its post asked for (ADR 0070)`() {
        val liked = id("a1")
        val injected = DeckSteering().inject(
            listOf(
                DeckCard(post("s1"), DeckOrigin.Similar(liked, saved = false)),
                DeckCard(post("c1"), DeckOrigin.Creator(liked, "maker")),
                DeckCard(post("s2"), DeckOrigin.Similar(id("other"), saved = false)),
            ),
        )

        assertEquals(listOf("s1", "c1", "s2", "a2"), nextBatch(listOf(lane(a, "s1", "a2")), injected, { false }, 4).cards.map { it.post.id.sourcePostId })
        assertEquals(listOf("s2"), injected.undone(before = DeckSteering(), post = liked).pending.map { it.post.id.sourcePostId })
    }

    @Test fun `undo restores the steering from before the swipe and keeps this deal's credit (ADR 0070)`() {
        val before = DeckSteering().after(ForYouVerdictKind.SKIPPED, a).after(ForYouVerdictKind.SKIPPED, a)
        val swiped = before.after(ForYouVerdictKind.SKIPPED, a)
        val dealt = nextBatch(listOf(lane(a, "a1", "a2"), lane(b, "b1", "b2", "b3")), swiped, { false }, 2).steering
        assertEquals(0.5, dealt.weight("a"), 0.0)

        val undone = dealt.undone(before = before, post = id("a1"))

        assertEquals(1.0, undone.weight("a"), 0.0)
        assertEquals(mapOf("a" to 2), undone.skipRuns)
        assertEquals(mapOf("a" to -0.5, "b" to 0.5), undone.credit)
    }

    @Test fun `a chip deals one lane and batches continue the rotation (ADR 0070)`() {
        val lanes = listOf(lane(a, "a1", "a2"), lane(b, "b1", "b2"))
        assertEquals(listOf("b1", "b2"), nextBatch(lanes, DeckSteering(), { false }, 4, only = "b").cards.map { it.post.id.sourcePostId })

        val first = nextBatch(lanes, DeckSteering(), { false }, 1)
        val rest = listOf(lane(a, "a2"), lane(b, "b1", "b2"))
        assertEquals(listOf("a1", "b1"), first.cards.map { it.post.id.sourcePostId } + nextBatch(rest, first.steering, setOf(id("a1"))::contains, 1).cards.map { it.post.id.sourcePostId })
    }

    @Test fun `one picture is dealt once, and not at all when a copy was seen (ADR 0070)`() {
        val copies = mapOf("a1" to setOf("a1", "b1"), "b1" to setOf("a1", "b1"), "b2" to setOf("b2", "k"))
        val batch = nextBatch(
            listOf(lane(a, "a1", "a2"), lane(b, "b1", "b2", "b3")), DeckSteering(), setOf(id("k"))::contains, size = 4,
        ) { post -> copies[post.id.sourcePostId].orEmpty().mapTo(mutableSetOf(), ::id) + post.id }

        assertEquals(listOf("a1", "b3", "a2"), batch.cards.map { it.post.id.sourcePostId })
        assertEquals(mapOf("a" to 2, "b" to 3), batch.taken)
    }

    @Test fun `an injected picture's copies are not dealt again from a lane (ADR 0070)`() {
        val injected = DeckSteering().inject(listOf(DeckCard(post("s1"), DeckOrigin.Similar(id("liked"), saved = false))))

        val batch = nextBatch(listOf(lane(a, "a1", "a2")), injected, { false }, size = 2) { post ->
            if (post.id == id("s1")) setOf(id("s1"), id("a1")) else setOf(post.id)
        }

        assertEquals(listOf("s1", "a2"), batch.cards.map { it.post.id.sourcePostId })
    }

    private fun lane(origin: DeckOrigin.Lane, vararg ids: String) = DeckLane(origin, ids.map(::post))
    private fun id(value: String) = PostId(SourceKey.PIXIV, value)
    private fun post(value: String) = Post(
        id = id(value),
        preview = ImageRef("https://example.com/$value.jpg", null, "image/jpeg"),
        full = null,
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )
}
