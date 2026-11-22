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

/** The Mix with the picture tagger ready: lane posts are dealt only once their pictures are tagged. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForYouDeckVettingTest {
    private val engine = FakeForYouEngine(listOf("a", "b", "c"))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun tearDown() {
        writeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun owner(pictures: FakePictureTags, environment: ForYouEnvironment = deckEnvironment(hiding("x"))) =
        ForYouDeckViewModel(engine, deckWiring(writeScope, pictures = pictures)).also { it.synchronize(environment) }

    private val ForYouDeckViewModel.posts get() = levels.value.last().feed.value.posts
    private val ForYouDeckViewModel.ids get() = posts.map { it.id.sourcePostId }

    @Test fun `a post whose picture carries a hidden tag is never dealt, though its provider never tagged it`() {
        val pictures = FakePictureTags(predicted = mapOf("b-0-0" to seen("x"), "a-0-1" to seen("y")))
        val owner = owner(pictures)

        assertEquals(4, owner.posts.size)
        assertFalse("b-0-0" in owner.ids)
        assertTrue("a-0-1" in owner.ids)
        // Every dealt card was tagged first.
        assertTrue(pictures.asked.containsAll(owner.ids))
    }

    @Test fun `nothing is dealt while its picture is being tagged`() {
        val pictures = FakePictureTags().apply { held = CompletableDeferred() }
        val owner = owner(pictures)

        assertEquals(emptyList<Post>(), owner.posts)
        assertTrue(owner.levels.value.last().feed.value.loadingMore)
        pictures.release()
        assertEquals(4, owner.posts.size)
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

    @Test fun `without the tagger the Mix deals as before and tags nothing`() {
        val pictures = FakePictureTags(ready = false, predicted = mapOf("b-0-0" to seen("x")))
        val owner = owner(pictures)
        assertEquals(listOf("a-0-0", "b-0-0", "c-0-0", "a-0-1"), owner.ids)
        assertEquals(emptyList<String>(), pictures.asked)
    }
}
