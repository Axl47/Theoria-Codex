package com.theoriacodex.app.recommend

import com.theoriacodex.app.codex.ChosenCodex
import com.theoriacodex.app.feed.FeedCursor
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.triage.TriageChoice
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ForYouVerdict
import com.theoriacodex.data.repository.ForYouVerdictKind
import com.theoriacodex.data.repository.InMemoryForYouVerdictRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The For You owner over fakes that answer at once, so each call settles before it returns. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForYouDeckViewModelTest {
    private val engine = FakeForYouEngine(listOf("a", "b", "c"))
    private val trails = FakeTrails()
    private val actions = FakeDeckActions()
    private val quickSave = FakeDeckQuickSave()
    private val verdicts = InMemoryForYouVerdictRepository()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    /** Outlives every owner, as the activity's scope does. */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun tearDown() {
        writeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun owner(environment: ForYouEnvironment = deckEnvironment(), copiesOf: (Post) -> Set<PostId> = { setOf(it.id) }) =
        ForYouDeckViewModel(engine, deckWiring(writeScope, trails, actions, quickSave, verdicts, copiesOf)).also { it.synchronize(environment) }

    private val ForYouDeckViewModel.top get() = levels.value.last()
    private val ForYouDeckViewModel.posts get() = top.feed.value.posts
    private val ForYouDeckViewModel.current get() = checkNotNull(top.deck.state.value.current)

    /** Decides the top level's current card and returns it. */
    private fun ForYouDeckViewModel.decide(choice: TriageChoice): Post = current.also { top.deck.decide(choice, it.id) }

    private fun ForYouDeckViewModel.skip(count: Int) = repeat(count) { decide(TriageChoice.Skip) }

    @Test fun `the Mix deals its lanes in turn and knows which lane gave each card`() {
        val owner = owner()
        assertEquals(listOf("a-0-0", "b-0-0", "c-0-0", "a-0-1"), owner.posts.map { it.id.sourcePostId })
        assertEquals(DeckOrigin.Lane(laneId("b"), "b"), owner.originOf(owner.posts[1].id))
        assertEquals(listOf("b"), owner.laneTags(owner.posts[1].id))
        assertEquals(listOf("a", "b", "c"), owner.state.value.chips.map(ForYouChip::label))
        assertEquals(owner.top.id, owner.viewerFeed.queryHash)
    }

    @Test fun `a small batch is released as the deck nears its end and lanes running low ask for a page`() {
        val owner = owner()
        assertEquals(listOf("a:0", "b:0", "c:0"), engine.requests)
        owner.skip(1)
        assertEquals(4, owner.posts.size)
        owner.skip(1)
        assertEquals(listOf("a", "b", "c", "a", "b", "c", "a", "b"), owner.posts.lanes())
        // Lanes a and b each gave three of their eight posts; c gave two and still holds enough.
        assertEquals(listOf("a:0", "b:0", "c:0", "a:1", "b:1"), engine.requests)
    }

    @Test fun `the grid takes a screenful per deal`() {
        assertEquals(12, owner(deckEnvironment(grid = true)).posts.size)
    }

    @Test fun `a chip narrows the Mix to its lane and the undecided cards are dealt again after`() {
        val owner = owner()
        val mix = owner.top.id
        owner.selectChip(laneId("b"))
        assertEquals(laneId("b"), owner.state.value.only)
        assertEquals(listOf("b-0-0", "b-0-1", "b-0-2", "b-0-3"), owner.posts.map { it.id.sourcePostId })
        assertNotEquals(mix, owner.top.id)

        val skipped = owner.decide(TriageChoice.Skip)
        owner.selectChip(null)
        assertNull(owner.state.value.only)
        assertEquals(setOf("a", "b", "c"), owner.posts.lanes().toSet())
        assertFalse(skipped in owner.posts)
        assertEquals("a-0-0", owner.posts.first { it.id.sourcePostId.startsWith("a") }.id.sourcePostId)
    }

    @Test fun `a like asks for two unseen similar posts, which land a few cards later with their reason`() {
        val fresh = listOf(testPost(DECK_SOURCE, "s-1"), testPost(DECK_SOURCE, "s-2"), testPost(DECK_SOURCE, "s-3"))
        val owner = owner()
        val liked = owner.current
        // The liked post itself and one the Mix already dealt are not new to the user.
        trails.similar = listOf(liked, owner.posts[2]) + fresh
        owner.decide(TriageChoice.Like)
        assertEquals(4, owner.posts.size)
        owner.skip(1)

        assertEquals(listOf("a", "b", "c", "a", "s", "s"), owner.posts.lanes().take(6))
        assertEquals(fresh.take(2), owner.posts.subList(4, 6))
        assertEquals(DeckOrigin.Similar(liked.id, saved = false), owner.originOf(fresh[0].id))
        assertEquals("Like one you liked", owner.originOf(fresh[0].id)?.label())
        assertEquals(listOf(liked.id), actions.liked)
    }

    @Test fun `similar posts a save asked for say it was a save`() {
        val fresh = testPost(DECK_SOURCE, "s-1")
        trails.similar = listOf(fresh)
        val owner = owner()
        val saved = owner.decide(TriageChoice.Save(ChosenCodex.Existing("codex-1", "Skies")))
        owner.skip(1)

        assertEquals(DeckOrigin.Similar(saved.id, saved = true), owner.originOf(fresh.id))
        assertEquals("Like one you saved", owner.originOf(fresh.id)?.label())
    }

    @Test fun `a save on a source without similar posts asks its creator instead`() {
        trails.creator = listOf(testPost(DECK_SOURCE, "s-1"))
        val owner = owner(deckEnvironment(creatorBrowsingSources = setOf(DECK_SOURCE)))
        val saved = owner.decide(TriageChoice.Save(ChosenCodex.Existing("codex-1", "Skies")))
        owner.skip(1)

        assertEquals(DeckOrigin.Creator(saved.id, "artist"), owner.originOf(owner.posts[4].id))
        assertEquals("More by artist", owner.originOf(owner.posts[4].id)?.label())
        assertEquals(listOf(saved.id), quickSave.saved)
        assertTrue(quickSave.fromForYou)
    }

    @Test fun `a run of skips lowers its lane and writes nothing`() {
        engine.tags = listOf("a", "b")
        val owner = owner()
        // Lane a is skipped three times in a row while lane b is liked.
        repeat(3) {
            owner.decide(TriageChoice.Skip)
            owner.decide(TriageChoice.Like)
        }
        assertEquals(listOf("b", "a", "b", "b"), owner.posts.lanes().subList(8, 12))
        assertEquals(3, actions.liked.size)
        assertTrue(actions.hidden.isEmpty() && quickSave.saved.isEmpty() && trails.asked.none { it.from.id.sourcePostId.startsWith("a") })
    }

    @Test fun `undoing the skip that lowered a lane gives the lane its share back`() {
        engine.tags = listOf("a", "b")
        val owner = owner()
        repeat(2) {
            owner.decide(TriageChoice.Skip)
            owner.decide(TriageChoice.Like)
        }
        val third = owner.decide(TriageChoice.Skip)
        owner.top.deck.undo()
        assertSame(third, owner.current)
        owner.decide(TriageChoice.Like)
        owner.decide(TriageChoice.Like)
        assertEquals(listOf("a", "b", "a", "b"), owner.posts.lanes().subList(8, 12))
    }

    @Test fun `every swipe is logged under the profile with its preview, and undo removes it`() = runTest {
        val owner = owner()
        val liked = owner.decide(TriageChoice.Like)
        val skipped = owner.decide(TriageChoice.Skip)
        val undone = owner.decide(TriageChoice.Skip)
        owner.top.deck.undo()

        val profile = AppSettings().activeProfileId
        assertEquals(
            listOf(skipped.id to ForYouVerdictKind.SKIPPED, liked.id to ForYouVerdictKind.LIKED),
            verdicts.verdicts(profile).map { it.postId to it.kind },
        )
        assertEquals(liked.preview.url, verdicts.verdicts(profile).last().previewUrl)
        assertFalse(verdicts.verdicts(profile).any { it.postId == undone.id })
    }

    @Test fun `a copy of a kept post on another site is not dealt, nor a second copy of a dealt one`() {
        val kept = testPost(SourceKey.PIXIV, "kept")
        // b-0-0 is a repost of the kept post; c-0-0 is the same picture as a-0-0.
        val copies = mapOf("b-0-0" to setOf(kept.id), "c-0-0" to setOf(testPost(DECK_SOURCE, "a-0-0").id))
        val owner = owner(deckEnvironment(kept = setOf(kept.id))) { post -> copies[post.id.sourcePostId].orEmpty() + post.id }

        assertEquals(listOf("a-0-0", "b-0-1", "c-0-1", "a-0-1"), owner.posts.map { it.id.sourcePostId })
    }

    @Test fun `undoing a like takes back the posts it asked for`() {
        trails.similar = listOf(testPost(DECK_SOURCE, "s-1"), testPost(DECK_SOURCE, "s-2"))
        val owner = owner()
        val liked = owner.decide(TriageChoice.Like)
        owner.top.deck.undo()
        assertEquals(listOf(liked.id), actions.unliked)
        owner.skip(6)
        assertFalse("s" in owner.posts.lanes())
    }

    @Test fun `a like that fails to save brings its card back and takes back the posts it asked for`() {
        trails.similar = listOf(testPost(DECK_SOURCE, "s-1"), testPost(DECK_SOURCE, "s-2"))
        actions.failLike = true
        val owner = owner()
        val liked = owner.decide(TriageChoice.Like)
        assertSame(liked, owner.current)
        owner.skip(6)
        assertFalse("s" in owner.posts.lanes())
    }

    @Test fun `hiding a tag mid-session drops its lane, its chip and its cards`() {
        val owner = owner()
        owner.synchronize(deckEnvironment(hiding("b")))
        assertEquals(listOf("a", "c"), owner.state.value.chips.map(ForYouChip::label))
        assertEquals(listOf("a", "c", "a"), owner.posts.lanes())
        owner.skip(5)
        assertFalse("b" in owner.posts.lanes())
        // The lanes kept their place: nothing was asked for again.
        assertEquals(1, engine.builds)

        // Undo of the hide brings the lane back.
        owner.synchronize(deckEnvironment())
        assertEquals(listOf("a", "b", "c"), owner.state.value.chips.map(ForYouChip::label))
        owner.skip(4)
        assertTrue("b" in owner.posts.lanes().takeLast(4))
    }

    @Test fun `a post whose resolved copy carries a hidden tag leaves the level's feed and its deck`() {
        val owner = owner(deckEnvironment(hiding("x")))
        val post = owner.current
        owner.top.remember(post.copy(canonicalTags = post.canonicalTags + "x"))
        assertFalse(post.id in owner.posts.map(Post::id))
        assertNotEquals(post.id, owner.current.id)
    }

    @Test fun `a selected source that can no longer run reads as every source`() {
        val owner = owner(deckEnvironment().copy(selectedSource = SourceKey.PIXIV))
        assertNull(owner.state.value.selectedSource)
        assertEquals(4, owner.posts.size)
    }

    @Test fun `hiding the tag of the chip's lane widens the same deck to the Mix, and Undo brings the card back`() {
        val owner = owner()
        owner.selectChip(laneId("b"))
        val level = owner.top
        val hid = owner.decide(TriageChoice.Hide("b"))
        assertEquals(listOf(DECK_SOURCE to "b"), actions.hidden)
        // The hide reaches the owner through the profile's settings.
        owner.synchronize(deckEnvironment(hiding("b")))

        assertSame(level, owner.top)
        assertNull(owner.state.value.only)
        assertEquals(listOf("a", "c"), owner.state.value.chips.map(ForYouChip::label))
        assertEquals(setOf("a", "c"), owner.posts.lanes().toSet())
        // The deck moved on to a card the hidden tag does not hide.
        assertTrue(owner.current in owner.posts)

        level.deck.undo()
        assertSame(hid, owner.current)
        assertTrue(actions.hidden.isEmpty())
        owner.synchronize(deckEnvironment())
        assertSame(level, owner.top)
        assertSame(hid, owner.current)
        assertEquals(listOf("a", "b", "c"), owner.state.value.chips.map(ForYouChip::label))
    }

    @Test fun `removing a hide the Mix was built under brings its lane in and keeps the deck`() {
        val owner = owner(deckEnvironment(hiding("b")))
        assertEquals(listOf("a", "c"), owner.state.value.chips.map(ForYouChip::label))
        owner.skip(1)
        val level = owner.top
        val card = owner.current

        owner.synchronize(deckEnvironment())
        assertSame(level, owner.top)
        assertSame(card, owner.current)
        assertEquals(listOf("a", "c", "b"), owner.state.value.chips.map(ForYouChip::label))
        owner.skip(4)
        assertTrue("b" in owner.posts.lanes())
    }

    @Test fun `removing the hide that left the Mix without lanes builds it`() {
        engine.tags = listOf("b")
        val owner = owner(deckEnvironment(hiding("b")))
        assertEquals(ForYouMixState.NoLanes, owner.state.value.mix)

        owner.synchronize(deckEnvironment())
        assertEquals(ForYouMixState.Dealing, owner.state.value.mix)
        assertEquals(listOf("b", "b", "b", "b"), owner.posts.lanes())
    }

    @Test fun `a tag hidden again while its lane is planned stays out until its hide goes`() {
        val owner = owner(deckEnvironment(hiding("b")))
        val gate = CompletableDeferred<Unit>().also { engine.planGate = it }
        owner.synchronize(deckEnvironment())
        owner.synchronize(deckEnvironment(hiding("b")))
        engine.planGate = null
        gate.complete(Unit)
        assertEquals(listOf("a", "c"), owner.state.value.chips.map(ForYouChip::label))

        owner.synchronize(deckEnvironment())
        assertEquals(listOf("a", "c", "b"), owner.state.value.chips.map(ForYouChip::label))
    }

    @Test fun `hiding the last lane ends the grid instead of leaving it loading`() {
        engine.tags = listOf("a")
        val owner = owner(deckEnvironment(grid = true))
        assertTrue(owner.posts.isNotEmpty())

        owner.synchronize(deckEnvironment(hiding("a"), grid = true))
        val feed = owner.top.feed.value
        assertTrue(feed.posts.isEmpty())
        assertFalse(feed.loadingMore || feed.canLoadMore)
    }

    @Test fun `the first lane to answer deals, and lanes that answer later join the rotation`() {
        engine.hold("a", "b", "c")
        val owner = owner()
        assertTrue(owner.posts.isEmpty() && owner.top.feed.value.loadingMore)

        engine.release("b")
        assertEquals(listOf("b", "b", "b", "b"), owner.posts.lanes())
        engine.release("a")
        engine.release("c")
        owner.skip(2)
        assertEquals(listOf("a", "b", "c", "a"), owner.posts.lanes().drop(4))
    }

    @Test fun `lanes with the same tag on two sources say which source each is`() {
        val cursor = FeedCursor(Query.newest(DECK_SOURCE))
        val mix = ForYouMix(listOf(ForYouLane(DECK_SOURCE, listOf("fog"), cursor), ForYouLane(SourceKey.PIXIV, listOf("fog"), cursor), ForYouLane(SourceKey.PIXIV, listOf("sea"), cursor)))
        assertEquals(
            listOf("fog · ${DECK_SOURCE.displayName()}", "fog · ${SourceKey.PIXIV.displayName()}", "sea"),
            mix.lanes.map { it.origin.label },
        )
    }

    @Test fun `lanes added together with the same tag say which source each is`() {
        val cursor = FeedCursor(Query.newest(DECK_SOURCE))
        val mix = ForYouMix(listOf(ForYouLane(DECK_SOURCE, listOf("sea"), cursor)))
        mix.addLanes(listOf(ForYouLane(DECK_SOURCE, listOf("fog"), cursor), ForYouLane(SourceKey.PIXIV, listOf("fog"), cursor)))
        assertEquals(
            listOf("sea", "fog · ${DECK_SOURCE.displayName()}", "fog · ${SourceKey.PIXIV.displayName()}"),
            mix.lanes.map { it.origin.label },
        )
    }

    @Test fun `a fork opens a trail on top and leaving it returns to the Mix where it was`() {
        val trail = listOf(lanePost("a", 0, 3), testPost(DECK_SOURCE, "t-1"), testPost(DECK_SOURCE, "t-2"))
        trails.tag = trail
        val owner = owner()
        owner.skip(1)
        val mix = owner.top
        val card = owner.current
        owner.fork(TrailRequest.Tag("sea", DECK_SOURCE, context = null, from = card))

        assertEquals(listOf("Mix", "sea"), owner.levels.value.map(ForYouLevel::label))
        assertEquals(trail, owner.posts)
        assertEquals(owner.top.id, owner.viewerFeed.queryHash)
        assertEquals(listOf("sea"), owner.top.query?.includeTags)
        // Chips belong to the Mix; a trail keeps the one it was forked from.
        owner.selectChip(laneId("b"))
        assertEquals(2, owner.levels.value.size)

        owner.decide(TriageChoice.Skip)
        owner.popTo(0)
        assertSame(mix, owner.levels.value.single())
        assertSame(card, owner.current)
        assertEquals(mix.id, owner.viewerFeed.queryHash)
        // The post the trail's skip decided is never dealt by the Mix.
        owner.skip(7)
        assertTrue(owner.posts.size >= 12)
        assertFalse(trail[0] in owner.posts)
    }

    @Test fun `a trail leaves out what was decided or kept before it opened, and keeps the cards decided on it`() {
        val keptBefore = testPost(DECK_SOURCE, "t-kept")
        val fresh = listOf(testPost(DECK_SOURCE, "t-1"), testPost(DECK_SOURCE, "t-2"))
        val owner = owner(deckEnvironment(kept = setOf(keptBefore.id)))
        val skipped = owner.decide(TriageChoice.Skip)
        trails.tag = listOf(skipped, keptBefore) + fresh
        owner.fork(TrailRequest.Tag("sea", DECK_SOURCE, context = null, from = owner.current))
        assertEquals(fresh, owner.posts)

        val liked = owner.decide(TriageChoice.Like)
        // The like reaches the owner as a kept post; the trail still holds its card for Undo.
        owner.synchronize(deckEnvironment(kept = setOf(keptBefore.id, liked.id)))
        assertEquals(fresh, owner.posts)
        owner.top.deck.undo()
        assertSame(liked, owner.current)
    }

    @Test fun `a second fork while one resolves does nothing`() {
        trails.tag = listOf(testPost(DECK_SOURCE, "t-1"))
        val gate = CompletableDeferred<Unit>().also { trails.gate = it }
        val owner = owner()
        val request = TrailRequest.Tag("sea", DECK_SOURCE, context = null, from = owner.current)
        owner.fork(request)
        owner.fork(request)
        gate.complete(Unit)

        assertEquals(1, trails.asked.size)
        assertEquals(listOf("Mix", "sea"), owner.levels.value.map(ForYouLevel::label))
    }

    @Test fun `a fork that resolves after its level was replaced is dropped`() {
        trails.tag = listOf(testPost(DECK_SOURCE, "t-1"))
        val gate = CompletableDeferred<Unit>().also { trails.gate = it }
        val owner = owner()
        owner.fork(TrailRequest.Tag("sea", DECK_SOURCE, context = null, from = owner.current))
        owner.selectChip(laneId("b"))
        gate.complete(Unit)

        assertEquals(listOf("Mix"), owner.levels.value.map(ForYouLevel::label))
        assertEquals(laneId("b"), owner.state.value.only)
    }

    @Test fun `a fork that cannot open says so and stays on the Mix`() = runTest {
        val owner = owner()
        owner.fork(TrailRequest.Similar(owner.current))
        assertEquals("Couldn't open similar posts", owner.messages.first())
        assertEquals(1, owner.levels.value.size)
    }

    @Test fun `a recreated owner deals a fresh Mix without the profile's recent skips`() {
        val first = owner()
        first.skip(3)
        val second = owner()
        assertNotEquals(first.top.id, second.top.id)
        assertEquals(listOf("a-0-1", "b-0-1", "c-0-1", "a-0-2"), second.posts.map { it.id.sourcePostId })
        assertEquals(0, second.top.deck.state.value.index)
        assertEquals(2, engine.builds)
    }

    @Test fun `a skip older than 30 days is dealt again, and another profile's skips are not read`() = runTest {
        val now = 40L * DAY_MS
        verdicts.record(ForYouVerdict("other", lanePost("a", 0, 1).id, ForYouVerdictKind.SKIPPED, now, null))
        verdicts.record(ForYouVerdict(AppSettings().activeProfileId, lanePost("a", 0, 0).id, ForYouVerdictKind.SKIPPED, now - 31 * DAY_MS, null))
        verdicts.record(ForYouVerdict(AppSettings().activeProfileId, lanePost("b", 0, 0).id, ForYouVerdictKind.SKIPPED, now - 29 * DAY_MS, null))
        val owner = ForYouDeckViewModel(engine, deckWiring(writeScope, trails, actions, quickSave, verdicts, clock = { now }))
        owner.synchronize(deckEnvironment())
        assertEquals(listOf("a-0-0", "b-0-1", "c-0-0", "a-0-1"), owner.posts.map { it.id.sourcePostId })
    }

    @Test fun `Start over deals a small source's skipped posts again, while Refresh keeps them out`() {
        val small = FakeForYouEngine(listOf("a"), pages = 1, pageSize = 4)
        fun smallOwner() = ForYouDeckViewModel(small, deckWiring(writeScope, trails, actions, quickSave, verdicts)).also { it.synchronize(deckEnvironment()) }
        smallOwner().skip(4)

        val next = smallOwner()
        assertTrue(next.posts.isEmpty())
        assertFalse(next.top.feed.value.canLoadMore)
        next.rebuild()
        assertTrue(next.posts.isEmpty())
        next.startOver()
        assertEquals(listOf("a-0-0", "a-0-1", "a-0-2", "a-0-3"), next.posts.map { it.id.sourcePostId })
    }

    @Test fun `a session's skips stay out of a refreshed Mix`() {
        val owner = owner()
        val skipped = List(2) { owner.decide(TriageChoice.Skip) }
        owner.rebuild()
        assertTrue(owner.posts.none { it in skipped })
    }

    @Test fun `posts the profile already liked or saved are not dealt`() {
        val kept = lanePost("b", 0, 0).id
        assertFalse(kept in owner(deckEnvironment(kept = setOf(kept))).posts.map(Post::id))
    }

    @Test fun `a lane that fails waits in the status row while the others deal, and Retry asks again`() {
        engine.failing += "b"
        val owner = owner()
        assertEquals(listOf(SourceRunState.FAILED), owner.state.value.failures.map { it.state })
        assertEquals(listOf("a", "c", "a", "c"), owner.posts.lanes())

        engine.failing.clear()
        owner.retrySource(DECK_SOURCE)
        assertTrue(owner.state.value.failures.isEmpty())
        owner.skip(2)
        assertTrue("b" in owner.posts.lanes())
    }

    @Test fun `a Mix with nothing to deal says why`() {
        engine.sources = emptyList()
        assertEquals(ForYouMixState.NoSources, owner().state.value.mix)

        engine.sources = listOf(DECK_SOURCE)
        engine.tags = emptyList()
        val empty = owner()
        assertEquals(ForYouMixState.NoLanes, empty.state.value.mix)
        assertFalse(empty.top.feed.value.canLoadMore)
    }

    @Test fun `lanes that cannot be built fail the Mix until Retry builds them`() {
        engine.failLanes = true
        val owner = owner()
        assertEquals(ForYouMixState.Failed, owner.state.value.mix)
        assertTrue(owner.top.feed.value.pageFailed)

        engine.failLanes = false
        owner.top.retryPage()
        assertEquals(ForYouMixState.Dealing, owner.state.value.mix)
        assertEquals(4, owner.posts.size)
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000
