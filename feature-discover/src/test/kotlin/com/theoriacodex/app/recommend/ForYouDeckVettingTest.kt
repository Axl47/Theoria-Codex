package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForYouDeckVettingTest {
    private val engine = FakeForYouEngine(listOf("a", "b", "c"))

    private val main = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun tearDown() {
        writeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun owner(pictures: FakePictureTags, environment: ForYouEnvironment = deckEnvironment(hiding("x"))) =
        ForYouDeckViewModel(engine, deckWiring(writeScope, pictures = pictures)).also { it.synchronize(environment) }

    private val ForYouDeckViewModel.posts get() = levels.value.last().feed.value.posts
    private val ForYouDeckViewModel.ids get() = posts.map { it.id.sourcePostId }

    @Test fun `a post whose picture carries a hidden tag is never dealt, though its provider never tagged it (ADR 0135)`() {
        val pictures = FakePictureTags(predicted = mapOf("b-0-0" to seen("x"), "a-0-1" to seen("y")))
        val owner = owner(pictures)

        assertEquals(4, owner.posts.size)
        assertFalse("b-0-0" in owner.ids)
        assertTrue("a-0-1" in owner.ids)
        assertTrue(pictures.asked.containsAll(owner.ids))
    }

    @Test fun `the first deal mixes lanes though every card waits for its picture`() {
        val owner = owner(FakePictureTags())
        assertEquals(4, owner.posts.size)
        assertTrue(owner.posts.lanes().toString(), owner.posts.lanes().distinct().size > 1)
    }

    @Test fun `a lane whose pictures a hidden tag catches stops paging after three pages that give nothing`() {
        val deep = FakeForYouEngine(listOf("a"), pages = 10)
        val caught = (0 until 10).flatMap { page -> List(FakeForYouEngine.PAGE) { lanePost("a", page, it).id.sourcePostId } }
        val owner = ForYouDeckViewModel(deep, deckWiring(writeScope, pictures = FakePictureTags(predicted = caught.associateWith { seen("x") })))
            .also { it.synchronize(deckEnvironment(hiding("x"))) }

        assertEquals(listOf("a:0", "a:1", "a:2"), deep.requests.filter { it.startsWith("a:") })
        assertEquals(emptyList<Post>(), owner.posts)
        assertFalse(owner.levels.value.last().feed.value.loadingMore)
    }

    @Test fun `nothing is dealt while its picture is being tagged (ADR 0135)`() {
        val pictures = FakePictureTags().apply { held = CompletableDeferred() }
        val owner = owner(pictures)

        assertEquals(emptyList<Post>(), owner.posts)
        assertTrue(owner.levels.value.last().feed.value.loadingMore)
        pictures.release()
        assertEquals(4, owner.posts.size)
    }

    @Test fun `posts the deal would pass over are never tagged`() {
        val kept = List(FakeForYouEngine.PAGE) { lanePost("a", 0, it).id }.toSet()
        val pictures = FakePictureTags()
        val owner = owner(pictures, deckEnvironment(hiding("x"), kept = kept))

        assertEquals(4, owner.posts.size)
        assertTrue(pictures.asked.none { id -> kept.any { it.sourcePostId == id } })
    }

    @Test fun `a tagger that becomes ready vets what the lanes already hold`() {
        val pictures = FakePictureTags(ready = false, predicted = mapOf("a-0-2" to seen("x")))
        val owner = owner(pictures)
        assertEquals(4, owner.posts.size)

        pictures.ready.value = true
        val deck = owner.levels.value.last().deck
        repeat(8) { deck.decide(com.theoriacodex.app.triage.TriageChoice.Skip, checkNotNull(deck.state.value.current).id) }
        assertTrue(owner.posts.size > 8)
        assertFalse("a-0-2" in owner.ids)
    }

    @Test fun `a refresh that restarts vetting runs one loop though the first post deals again before the loop suspends (ADR 0135)`() {
        val pictures = FakePictureTags()
        val owner = owner(pictures)
        pictures.unseen = setOf("a-0-0")
        pictures.held = CompletableDeferred()
        val before = pictures.asked.size
        pictures.mostLooking = 0
        owner.rebuild()

        assertEquals(1, pictures.mostLooking)
        pictures.release()
        val restarted = pictures.asked.drop(before)
        assertTrue(restarted.isNotEmpty())
        assertEquals(restarted.distinct(), restarted)
    }

    @Test fun `vetting keeps ahead of the deal through pages far bigger than its look-ahead`() {
        val big = FakeForYouEngine(listOf("a", "b", "c"), pages = 1, pageSize = 24)
        val owner = ForYouDeckViewModel(big, deckWiring(writeScope, pictures = FakePictureTags())).also { it.synchronize(deckEnvironment(hiding("x"))) }
        val deck = owner.levels.value.last().deck
        repeat(20) { deck.decide(com.theoriacodex.app.triage.TriageChoice.Skip, checkNotNull(deck.state.value.current).id) }
        assertTrue(owner.posts.size > 20)
    }

    @Test fun `posts their provider tagged richly are dealt without a picture check while sparse ones wait for theirs`() {
        val rich = FakeForYouEngine(listOf(RICH, "a"))
        val pictures = FakePictureTags().apply { held = CompletableDeferred() }
        val owner = ForYouDeckViewModel(rich, deckWiring(writeScope, pictures = pictures)).also { it.synchronize(deckEnvironment(hiding("x"))) }

        assertTrue(owner.ids.isNotEmpty())
        assertTrue(owner.ids.all { it.startsWith(RICH) })
        assertTrue(pictures.asked.none { it.startsWith(RICH) })
        pictures.release()
        val deck = owner.levels.value.last().deck
        repeat(6) { deck.decide(com.theoriacodex.app.triage.TriageChoice.Skip, checkNotNull(deck.state.value.current).id) }
        assertTrue(owner.ids.any { it.startsWith("a-") })
    }

    @Test fun `a profile that hides nothing is dealt without tagging (ADR 0135)`() {
        val pictures = FakePictureTags()
        val owner = owner(pictures, deckEnvironment())
        assertEquals(4, owner.posts.size)
        assertEquals(emptyList<String>(), pictures.asked)
    }

    @Test fun `cards dealt before the first hidden tag are vetted when a refresh deals them again`() {
        val pictures = FakePictureTags(predicted = mapOf("b-0-0" to seen("x")))
        val owner = owner(pictures, deckEnvironment())
        assertTrue("b-0-0" in owner.ids)

        owner.synchronize(deckEnvironment(hiding("x")))
        owner.rebuild()

        assertTrue(owner.posts.isNotEmpty())
        assertFalse("b-0-0" in owner.ids)
    }

    @Test fun `a post whose preview did not load is left out, since its picture may carry a hidden tag (ADR 0135)`() {
        val pictures = FakePictureTags().apply { unseen = setOf("a-0-1") }
        val owner = owner(pictures)
        assertEquals(4, owner.posts.size)
        assertFalse("a-0-1" in owner.ids)
    }

    @Test fun `nothing is dealt while the model will not load, and dealing resumes once it does (ADR 0135)`() {
        val pictures = FakePictureTags().apply { blind = true }
        val owner = owner(pictures)
        assertEquals(emptyList<Post>(), owner.posts)

        pictures.blind = false
        main.scheduler.advanceTimeBy(10_000)
        main.scheduler.runCurrent()
        assertEquals(4, owner.posts.size)
    }

    @Test fun `without the tagger the Mix deals as before and tags nothing`() {
        val pictures = FakePictureTags(ready = false, predicted = mapOf("b-0-0" to seen("x")))
        val owner = owner(pictures)
        assertEquals(listOf("a-0-0", "b-0-0", "c-0-0", "a-0-1"), owner.ids)
        assertEquals(emptyList<String>(), pictures.asked)
    }
}
