package com.theoriacodex.app.recommend

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.triage.TriageChoice
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ForYouVerdict
import com.theoriacodex.data.repository.ForYouVerdictKind
import com.theoriacodex.data.repository.ForYouVerdictRepository
import com.theoriacodex.data.repository.InMemoryForYouVerdictRepository
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForYouLastDeckTest {
    private val lanes = FakeForYouEngine(listOf("a", "b", "c"))
    private val engine = DeckKeepingEngine(lanes)
    private val verdicts = InMemoryForYouVerdictRepository()
    private val trails = FakeTrails()
    private val reconnections = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun tearDown() {
        writeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun owner(environment: ForYouEnvironment = deckEnvironment(), verdicts: ForYouVerdictRepository = this.verdicts) =
        ForYouDeckViewModel(engine, deckWiring(writeScope, trails, verdicts = verdicts, reconnections = reconnections)).also { it.synchronize(environment) }

    private val ForYouDeckViewModel.posts get() = levels.value.last().feed.value.posts
    private val ForYouDeckViewModel.current get() = checkNotNull(levels.value.last().deck.state.value.current)

    private fun ForYouDeckViewModel.skip(count: Int) = repeat(count) { levels.value.last().deck.decide(TriageChoice.Skip, current.id) }

    private fun leftUndecided(): List<Post> {
        val first = owner()
        first.skip(2)
        return first.posts.drop(2).also { lanes.planGate = CompletableDeferred() }
    }

    private fun List<Post>.ids() = map { it.id.sourcePostId }

    @Test fun `the cards a session left undecided lead the next launch, before its lanes are planned, and fresh cards follow`() {
        val undecided = leftUndecided()
        val next = owner()
        assertEquals(undecided.ids(), next.posts.ids())
        assertEquals(undecided.first().id, next.current.id)

        checkNotNull(lanes.planGate).complete(Unit)
        assertEquals(undecided.ids(), next.posts.take(undecided.size).ids())
        assertTrue(next.posts.size > undecided.size)
        assertEquals(next.posts.size, next.posts.distinctBy(Post::id).size)
    }

    @Test fun `cards skipped, liked or saved since they were kept are left out`() = runTest {
        val undecided = leftUndecided()
        verdicts.record(ForYouVerdict(AppSettings().activeProfileId, undecided[0].id, ForYouVerdictKind.SKIPPED, 1, null))
        val next = owner(deckEnvironment(kept = setOf(undecided[1].id)))
        assertEquals(undecided.drop(2).ids(), next.posts.ids())
    }

    @Test fun `no kept card shows until the recent skips are read, nor at all when the read runs late`() = runTest {
        leftUndecided()
        val slow = CompletableDeferred<Unit>()
        val held = object : ForYouVerdictRepository by verdicts {
            override suspend fun skippedBetween(profileId: String, sinceEpochMs: Long, untilEpochMs: Long, limit: Int): Set<PostId> {
                slow.await()
                return verdicts.skippedBetween(profileId, sinceEpochMs, untilEpochMs, limit)
            }
        }
        val next = owner(verdicts = held)
        assertTrue(next.posts.isEmpty())
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertTrue(next.posts.isEmpty())
        slow.complete(Unit)
        assertTrue(next.posts.isEmpty())
    }

    @Test fun `another profile never sees the deck`() {
        leftUndecided()
        val other = AppSettings().let { it.copy(recommendationProfiles = it.recommendationProfiles + RecommendationProfile("other", "Other")) }
        assertTrue(owner(deckEnvironment(other.copy(activeProfileId = "other"))).posts.isEmpty())
    }

    @Test fun `Refresh deals a fresh Mix without painting the kept cards again`() {
        val undecided = leftUndecided()
        val next = owner()
        assertEquals(undecided.ids(), next.posts.ids())
        checkNotNull(lanes.planGate).complete(Unit)
        lanes.planGate = CompletableDeferred()
        next.rebuild()
        assertTrue(next.posts.isEmpty())
    }

    @Test fun `liking a kept card takes it out of the deck the next launch shows`() {
        val undecided = leftUndecided()
        lanes.planGate = null
        owner().let { it.levels.value.last().deck.decide(TriageChoice.Like, it.current.id) }
        lanes.planGate = CompletableDeferred()
        assertFalse(undecided.first() in owner().posts)
    }

    @Test fun `cards dealt behind the kept ones lead the launch after, so kept cards cannot pin the deck`() {
        val undecided = leftUndecided()
        val next = owner()
        checkNotNull(lanes.planGate).complete(Unit)
        val dealtBehind = next.posts.drop(undecided.size)
        lanes.planGate = CompletableDeferred()
        val after = owner().posts
        assertEquals(dealtBehind.ids(), after.take(dealtBehind.size).ids())
        assertEquals(undecided.ids(), after.drop(dealtBehind.size).take(undecided.size).ids())
    }

    @Test fun `liking a kept card still asks for similar posts`() {
        leftUndecided()
        val next = owner()
        checkNotNull(lanes.planGate).complete(Unit)
        val liked = next.current
        trails.similar = listOf(testPost(DECK_SOURCE, "s-1"), testPost(DECK_SOURCE, "s-2"))
        next.levels.value.last().deck.decide(TriageChoice.Like, liked.id)
        assertEquals(listOf<TrailRequest>(TrailRequest.Similar(liked)), trails.asked)
    }

    @Test fun `Refresh before the lanes are planned deals a fresh Mix instead of the kept cards`() {
        val undecided = leftUndecided()
        val next = owner()
        assertEquals(undecided.ids(), next.posts.ids())
        next.rebuild()
        assertTrue(next.posts.isEmpty())
    }

    @Test fun `kept cards stay on screen when lane planning fails`() {
        val undecided = leftUndecided()
        val next = owner()
        lanes.failLanes = true
        checkNotNull(lanes.planGate).complete(Unit)
        assertEquals(ForYouMixState.Failed, next.state.value.mix)
        assertEquals(undecided.ids(), next.posts.ids())
        assertTrue(next.levels.value.last().feed.value.pageFailed)

        reconnections.tryEmit(Unit)
        assertEquals(undecided.ids(), next.posts.ids())
    }

    @Test fun `a card liked from the grid leaves the kept deck`() {
        val first = owner()
        val liked = first.posts.first()
        first.synchronize(deckEnvironment(kept = setOf(liked.id)))
        lanes.planGate = CompletableDeferred()
        assertFalse(liked in owner(deckEnvironment()).posts)
    }

    @Test fun `liking a kept card before the lanes are planned asks for similar posts once they are`() {
        leftUndecided()
        val next = owner()
        val liked = next.current
        trails.similar = listOf(testPost(DECK_SOURCE, "s-1"), testPost(DECK_SOURCE, "s-2"))
        next.levels.value.last().deck.decide(TriageChoice.Like, liked.id)
        assertTrue(trails.asked.isEmpty())
        checkNotNull(lanes.planGate).complete(Unit)
        assertEquals(listOf<TrailRequest>(TrailRequest.Similar(liked)), trails.asked)
    }

    private class DeckKeepingEngine(private val lanes: FakeForYouEngine) : ForYouRouteEngine by lanes {
        private val decks = mutableMapOf<String, List<Post>>()

        override suspend fun lastDeck(request: ForYouFeedRequest): List<Post> = decks[request.deckKey()].orEmpty()

        override suspend fun rememberDeck(request: ForYouFeedRequest, posts: List<Post>) {
            decks[request.deckKey()] = posts
        }
    }
}
