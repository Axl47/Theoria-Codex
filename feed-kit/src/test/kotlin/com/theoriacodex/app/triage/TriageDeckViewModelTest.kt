package com.theoriacodex.app.triage

import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.app.codex.QuickSaveRecord
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TriageDeckViewModelTest {
    private val posts = (1..8).map { testPost(sourcePostId = "p$it") }
    private val actions = FakeActions()
    private val quickSave = FakeQuickSave()
    private val feed = FakeFeed(posts.take(3), canLoadMore = false)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun deck(source: TriageFeed = feed) = TriageDeckViewModel(source, actions, quickSave, query = null, fromForYou = false)

    @Test fun `like moves on and likes the post once`() = runTest {
        val deck = deck()
        deck.decide(TriageChoice.Like)
        assertEquals(1, deck.state.value.index)
        assertEquals(listOf(posts[0].id), actions.liked)
        assertEquals(TriageDecision.Liked(posts[0], newLike = true), deck.state.value.last)
    }

    @Test fun `an already liked post stays liked and undo leaves it so`() = runTest {
        actions.alreadyLiked += posts[0].id
        val deck = deck()
        deck.decide(TriageChoice.Like)
        deck.undo()
        assertEquals(emptyList<PostId>(), actions.unliked)
        assertEquals(0, deck.state.value.index)
    }

    @Test fun `undoing a like this deck added removes it`() = runTest {
        val deck = deck()
        deck.decide(TriageChoice.Like)
        deck.undo()
        assertEquals(listOf(posts[0].id), actions.unliked)
        assertEquals(0, deck.state.value.index)
        assertNull(deck.state.value.last)
    }

    @Test fun `skip only moves on, and undo brings the card back`() = runTest {
        val deck = deck()
        deck.decide(TriageChoice.Skip)
        assertEquals(1, deck.state.value.index)
        deck.undo()
        assertEquals(0, deck.state.value.index)
        assertTrue(actions.liked.isEmpty() && quickSave.saved.isEmpty() && actions.hidden.isEmpty())
    }

    @Test fun `save goes to the chosen Codex with the feed's query and undoes through quick save`() = runTest {
        val query = Query.newest(SourceKey.PIXIV)
        val deck = TriageDeckViewModel(feed, actions, quickSave, query, fromForYou = true)
        deck.decide(TriageChoice.Save("codex-1", "Skies"))
        assertEquals(listOf(Triple(posts[0].id, "codex-1", query)), quickSave.saved)
        assertTrue(quickSave.fromForYou)
        assertTrue(deck.state.value.last!!.undoable)
        deck.undo()
        assertEquals(1, quickSave.undone)
        assertEquals(0, deck.state.value.index)
    }

    @Test fun `a failed save leaves the card in place`() = runTest {
        quickSave.fail = true
        val deck = deck()
        deck.decide(TriageChoice.Save("codex-1", "Skies"))
        assertEquals(0, deck.state.value.index)
        assertNull(deck.state.value.last)
    }

    @Test fun `hide blacklists the tag for the post's source and undo removes it`() = runTest {
        val deck = deck()
        deck.decide(TriageChoice.Hide("cat"))
        assertEquals(listOf(SourceKey.PIXIV to "cat"), actions.hidden)
        deck.undo()
        assertEquals(emptyList<Pair<SourceKey, String>>(), actions.hidden)
        assertEquals(0, deck.state.value.index)
    }

    @Test fun `undo is one level and does nothing while a decision is still settling`() = runTest {
        val deck = deck()
        deck.decide(TriageChoice.Skip)
        deck.decide(TriageChoice.Skip)
        deck.undo()
        deck.undo()
        assertEquals(1, deck.state.value.index)

        actions.holdHide = true
        deck.decide(TriageChoice.Hide("cat"))
        assertFalse(deck.state.value.last!!.undoable)
        deck.undo()
        assertEquals(2, deck.state.value.index)
    }

    @Test fun `the deck asks a live feed for its next page near the end, once per size`() = runTest {
        val live = FakeFeed(posts.take(7), canLoadMore = true)
        val deck = deck(live)
        assertEquals(0, live.pagesRequested)
        deck.decide(TriageChoice.Skip)
        deck.decide(TriageChoice.Skip)
        assertEquals(1, live.pagesRequested)
        deck.decide(TriageChoice.Skip)
        assertEquals(1, live.pagesRequested)
        live.emit(posts, canLoadMore = false)
        assertEquals(8, deck.state.value.posts.size)
        assertEquals(3, deck.state.value.index)
    }

    @Test fun `a repeated snapshot never duplicates posts and the deck ends when the feed has no more`() = runTest {
        val deck = deck()
        feed.emit(posts.take(3), canLoadMore = false)
        assertEquals(3, deck.state.value.posts.size)
        repeat(3) { deck.decide(TriageChoice.Skip) }
        assertNull(deck.state.value.current)
        assertTrue(deck.state.value.finished)
        deck.undo()
        assertEquals(2, deck.state.value.index)
        assertFalse(deck.state.value.finished)
    }

    @Test fun `past the last post a feed that can load more is not finished`() = runTest {
        val deck = deck(FakeFeed(posts.take(1), canLoadMore = true))
        deck.decide(TriageChoice.Skip)
        assertFalse(deck.state.value.finished)
    }

    private class FakeFeed(initial: List<Post>, canLoadMore: Boolean) : TriageFeed {
        private val flow = MutableStateFlow(TriageFeedSnapshot(initial, canLoadMore))
        var pagesRequested = 0
        override val snapshots: Flow<TriageFeedSnapshot> = flow
        override fun loadNextPage() { pagesRequested++ }
        fun emit(posts: List<Post>, canLoadMore: Boolean) { flow.value = TriageFeedSnapshot(posts, canLoadMore) }
    }

    private class FakeActions : TriageActions {
        val alreadyLiked = mutableSetOf<PostId>()
        val liked = mutableListOf<PostId>()
        val unliked = mutableListOf<PostId>()
        val hidden = mutableListOf<Pair<SourceKey, String>>()
        var holdHide = false
        override val hiddenTags: Flow<Set<HiddenTag>> = flowOf(emptySet())
        override suspend fun like(post: Post): Boolean = alreadyLiked.add(post.id).also { if (it) liked += post.id }
        override fun unlike(post: Post) { unliked += post.id }
        override suspend fun hide(source: SourceKey, tag: String): Boolean {
            if (holdHide) kotlinx.coroutines.awaitCancellation()
            return hidden.add(source to tag)
        }
        override suspend fun unhide(source: SourceKey, tag: String) { hidden.remove(source to tag) }
    }

    private class FakeQuickSave : QuickSave {
        val saved = mutableListOf<Triple<PostId, String, Query?>>()
        var undone = 0
        var fail = false
        var fromForYou = false
        override val lastQuickSave: StateFlow<QuickSaveRecord?> = MutableStateFlow(null)
        override suspend fun quickSave(post: Post, codexId: String, query: Query?, fromForYou: Boolean): QuickSaveRecord? {
            if (fail) return null
            this.fromForYou = fromForYou
            saved += Triple(post.id, codexId, query)
            return QuickSaveRecord(post, codexId, "Skies", inserted = 1, savedAtEpochMs = 0L)
        }
        override fun undoLastQuickSave() { undone++ }
        override fun suggestions(post: Post, query: Query?, limit: Int): Flow<SaveSuggestions> = flowOf(SaveSuggestions.Empty)
    }
}
