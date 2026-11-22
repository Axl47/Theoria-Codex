package com.theoriacodex.app.triage

import com.theoriacodex.app.codex.ChosenCodex
import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.app.codex.QuickSaveRecord
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
class TriageDeckTest {
    private val posts = (1..8).map { testPost(sourcePostId = "p$it") }
    private val actions = FakeActions()
    private val quickSave = FakeQuickSave()
    private val feed = FakeFeed(posts.take(3), canLoadMore = false)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    /** Collects each deck's feed, as its owner's scope does. */
    private val deckScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Outlives every deck, as the activity's scope does. */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Every decision event the decks report, in order. */
    private val events = mutableListOf<TriageDecisionEvent>()

    @After fun tearDown() {
        deckScope.cancel()
        writeScope.cancel()
        Dispatchers.resetMain()
    }

    /** What the deck's owner hides, for the decks that have such an owner. */
    private val ownerHidden = MutableStateFlow(emptyMap<SourceKey, Set<String>>())

    private fun deck(source: TriageFeed = feed, removal: TriageRemoval? = null, query: Query? = null, fromForYou: Boolean = false) =
        TriageDeck(deckScope, source, actions, quickSave, query, fromForYou, writeScope, removal, onDecision = events::add, ownerHidden = ownerHidden)

    /** A deck on a Codex, where left removes through [removal]. */
    private fun removingDeck(removal: TriageRemoval) = deck(removal = removal)

    @Test fun `like moves on, likes the post once and confirms once committed`() = runTest {
        val deck = deck()
        deck.decideCurrent(TriageChoice.Like)
        assertEquals(1, deck.state.value.index)
        assertEquals(listOf(posts[0].id), actions.liked)
        assertEquals(TriageNotice(TriageDecision.Liked(posts[0]), failed = false), deck.state.value.notice)
        assertEquals(listOf(TriageDecisionEvent.Decided(TriageDecision.Liked(posts[0]))), events)
    }

    @Test fun `an already liked post stays liked and undo leaves it so`() = runTest {
        actions.alreadyLiked += posts[0].id
        val deck = deck()
        deck.decideCurrent(TriageChoice.Like)
        deck.undo()
        assertEquals(emptyList<PostId>(), actions.unliked)
        assertEquals(0, deck.state.value.index)
    }

    @Test fun `undoing a like this deck added removes it and clears the confirmation`() = runTest {
        val deck = deck()
        deck.decideCurrent(TriageChoice.Like)
        deck.undo()
        assertEquals(listOf(posts[0].id), actions.unliked)
        assertEquals(0, deck.state.value.index)
        assertNull(deck.state.value.last)
        assertNull(deck.state.value.notice)
    }

    @Test fun `skip only moves on, says nothing, and undo brings the card back`() = runTest {
        val deck = deck()
        deck.decideCurrent(TriageChoice.Skip)
        assertEquals(1, deck.state.value.index)
        deck.undo()
        assertEquals(0, deck.state.value.index)
        assertTrue(actions.liked.isEmpty() && quickSave.saved.isEmpty() && actions.hidden.isEmpty())
    }

    @Test fun `save goes to the chosen Codex with the feed's query and undo removes exactly that save`() = runTest {
        val query = Query.newest(SourceKey.PIXIV)
        val deck = deck(query = query, fromForYou = true)
        deck.decideCurrent(TriageChoice.Save(ChosenCodex.Existing("codex-1", "Skies")))
        assertEquals(listOf(Triple(posts[0].id, "codex-1", query)), quickSave.saved)
        assertTrue(quickSave.fromForYou)
        deck.undo()
        assertEquals(quickSave.records, quickSave.undone)
        assertEquals(0, deck.state.value.index)
    }

    @Test fun `a failed write brings its card back and says it failed instead of confirming`() = runTest {
        quickSave.fail = true
        actions.failLike = true
        actions.failHide = true
        val deck = deck()
        listOf(
            TriageChoice.Save(ChosenCodex.Existing("codex-1", "Skies")) to TriageDecision.Saved(posts[0], "Skies"),
            TriageChoice.Like to TriageDecision.Liked(posts[0]),
            TriageChoice.Hide("cat") to TriageDecision.Hid(posts[0], "cat"),
        ).forEach { (choice, decision) ->
            deck.decideCurrent(choice)
            assertEquals(0, deck.state.value.index)
            assertNull(deck.state.value.last)
            assertEquals(TriageNotice(decision, failed = true), deck.state.value.notice)
        }
        assertTrue(deck.state.value.passedOverTags.isEmpty())
    }

    @Test fun `a write that fails after newer swipes says so and keeps the deck where it is`() = runTest {
        actions.failLike = true
        actions.hold = CompletableDeferred()
        val deck = deck()
        deck.decideCurrent(TriageChoice.Like)
        deck.decideCurrent(TriageChoice.Skip)
        actions.hold!!.complete(Unit)
        assertEquals(2, deck.state.value.index)
        assertEquals(TriageDecision.Skipped(posts[1]), deck.state.value.last)
        assertEquals(TriageNotice(TriageDecision.Liked(posts[0]), failed = true), deck.state.value.notice)
    }

    @Test fun `hide blacklists the tag, passes over later cards carrying it, and undo brings them back`() = runTest {
        val tagged = listOf(testPost(sourcePostId = "a", canonicalTags = listOf("cat")), testPost(sourcePostId = "b", canonicalTags = listOf("Cat")), posts[2])
        val deck = deck(FakeFeed(tagged, canLoadMore = false))
        deck.decideCurrent(TriageChoice.Hide("cat"))
        assertEquals(listOf(SourceKey.PIXIV to "cat"), actions.hidden)
        assertEquals(posts[2], deck.state.value.current)
        deck.undo()
        assertEquals(emptyList<Pair<SourceKey, String>>(), actions.hidden)
        assertEquals(0, deck.state.value.index)
        assertEquals(tagged[1], deck.state.value.next)
    }

    @Test fun `a hide passes over the tag only on the source it was hidden on`() = runTest {
        val elsewhere = testPost(SourceKey.GELBOORU, "g", canonicalTags = listOf("cat"))
        val tagged = listOf(testPost(sourcePostId = "a", canonicalTags = listOf("cat")), testPost(sourcePostId = "b", canonicalTags = listOf("cat")), elsewhere)
        val deck = deck(FakeFeed(tagged, canLoadMore = false))
        deck.decideCurrent(TriageChoice.Hide("cat"))
        assertEquals(elsewhere, deck.state.value.current)
    }

    @Test fun `tags the owner hides pass their cards over, also the one on top`() = runTest {
        val tagged = listOf(testPost(sourcePostId = "a", canonicalTags = listOf("Big_Cat")), testPost(sourcePostId = "b", canonicalTags = listOf("big cat")), posts[2])
        val deck = deck(FakeFeed(tagged, canLoadMore = false))
        assertEquals(tagged[0], deck.state.value.current)
        ownerHidden.value = mapOf(SourceKey.GELBOORU to setOf("big cat"))
        assertEquals(tagged[0], deck.state.value.current)
        ownerHidden.value = mapOf(SourceKey.PIXIV to setOf("big cat"))
        assertEquals(posts[2], deck.state.value.current)
    }

    @Test fun `a card whose newer copy from the feed carries a hidden tag is passed over, in place`() = runTest {
        val source = FakeFeed(posts, canLoadMore = false)
        val deck = deck(source)
        ownerHidden.value = mapOf(SourceKey.PIXIV to setOf("cat"))
        deck.decideCurrent(TriageChoice.Skip)
        source.emit(posts.map { if (it == posts[1]) it.copy(canonicalTags = listOf("cat")) else it }, canLoadMore = false)
        assertEquals(posts[2], deck.state.value.current)
        assertEquals(posts.size, deck.state.value.posts.size)
    }

    @Test fun `the card Undo brings back stays while the owner still hides its tag`() = runTest {
        val tagged = listOf(testPost(sourcePostId = "a", canonicalTags = listOf("cat")), testPost(sourcePostId = "b", canonicalTags = listOf("cat")), posts[2])
        val deck = deck(FakeFeed(tagged, canLoadMore = false))
        deck.decideCurrent(TriageChoice.Hide("cat"))
        // The owner learns of the stored hide, and of its removal, some time after the deck.
        ownerHidden.value = mapOf(SourceKey.PIXIV to setOf("cat"))
        deck.undo()
        assertEquals(tagged[0], deck.state.value.current)
        ownerHidden.value = mapOf(SourceKey.PIXIV to setOf("cat", "dog"))
        assertEquals(tagged[0], deck.state.value.current)
        assertEquals(posts[2], deck.state.value.next)
        ownerHidden.value = emptyMap()
        assertEquals(tagged[0], deck.state.value.current)
        assertEquals(tagged[1], deck.state.value.next)
    }

    @Test fun `a write goes to the profile that was active when its card was decided`() = runTest {
        actions.hold = CompletableDeferred()
        val deck = deck()
        deck.decideCurrent(TriageChoice.Like)
        actions.profile = "alt"
        deck.decideCurrent(TriageChoice.Hide("cat"))
        actions.hold!!.complete(Unit)
        assertEquals(listOf("main", "alt"), actions.wroteTo)
    }

    @Test fun `undo during a running save reverses it once it commits and never confirms it`() = runTest {
        quickSave.hold = CompletableDeferred()
        val deck = deck()
        deck.decideCurrent(TriageChoice.Save(ChosenCodex.Existing("codex-1", "Skies")))
        assertTrue(deck.state.value.last != null)
        assertNull(deck.state.value.notice)
        deck.undo()
        assertEquals(0, deck.state.value.index)
        assertTrue(quickSave.undone.isEmpty())
        quickSave.hold!!.complete(Unit)
        assertEquals(quickSave.records, quickSave.undone)
        assertNull(deck.state.value.notice)
    }

    @Test fun `undo is one level and works while a decision is still running`() = runTest {
        val deck = deck()
        deck.decideCurrent(TriageChoice.Skip)
        deck.decideCurrent(TriageChoice.Skip)
        deck.undo()
        deck.undo()
        assertEquals(1, deck.state.value.index)
        assertNull(deck.state.value.last)

        actions.hold = CompletableDeferred()
        deck.decideCurrent(TriageChoice.Like)
        deck.undo()
        assertEquals(1, deck.state.value.index)
        actions.hold!!.complete(Unit)
        assertEquals(listOf(posts[1].id), actions.liked)
        assertEquals(listOf(posts[1].id), actions.unliked)
    }

    @Test fun `on a Codex, remove takes the post out, confirms once committed, and undo puts it back`() = runTest {
        val removal = FakeRemoval()
        val deck = removingDeck(removal)
        assertTrue(deck.removes)
        deck.decideCurrent(TriageChoice.Remove)
        assertEquals(1, deck.state.value.index)
        assertEquals(listOf(posts[0].id), removal.removed)
        assertEquals(TriageNotice(TriageDecision.Removed(posts[0]), failed = false), deck.state.value.notice)
        deck.undo()
        assertEquals(listOf(posts[0].id), removal.restored)
        assertEquals(0, deck.state.value.index)
    }

    @Test fun `a failed removal brings its card back and says so`() = runTest {
        val deck = removingDeck(FakeRemoval(fail = true))
        deck.decideCurrent(TriageChoice.Remove)
        assertEquals(0, deck.state.value.index)
        assertEquals(TriageNotice(TriageDecision.Removed(posts[0]), failed = true), deck.state.value.notice)
        assertEquals(posts.take(3) to 0, deck.state.value.viewerLaunch())
    }

    @Test fun `the Viewer opened from a Codex deck leaves out a removed post until it is undone`() = runTest {
        val deck = removingDeck(FakeRemoval())
        deck.decideCurrent(TriageChoice.Skip)
        deck.decideCurrent(TriageChoice.Remove)
        assertEquals(listOf(posts[0], posts[2]) to 1, deck.state.value.viewerLaunch())
        deck.undo()
        assertEquals(posts.take(3) to 1, deck.state.value.viewerLaunch())
    }

    @Test fun `remove, undo and remove again run in order, so the post ends removed`() = runTest {
        val removal = FakeRemoval(restoreHold = CompletableDeferred())
        val deck = removingDeck(removal)
        deck.decideCurrent(TriageChoice.Remove)
        deck.undo()
        deck.decideCurrent(TriageChoice.Remove)
        assertEquals(listOf(posts[0].id), removal.removed)
        removal.restoreHold!!.complete(Unit)
        assertEquals(listOf(posts[0].id, posts[0].id), removal.removed)
        assertEquals(listOf(posts[0].id), removal.restored)
    }

    @Test fun `on a Codex, keep writes nothing, says Kept at once, and undo brings the card back`() = runTest {
        val removal = FakeRemoval()
        val deck = removingDeck(removal)
        deck.decideCurrent(TriageChoice.Keep)
        assertEquals(1, deck.state.value.index)
        assertEquals(TriageNotice(TriageDecision.Kept(posts[0]), failed = false), deck.state.value.notice)
        deck.undo()
        assertEquals(0, deck.state.value.index)
        assertNull(deck.state.value.notice)
        assertTrue(removal.removed.isEmpty() && removal.restored.isEmpty())
        assertTrue(actions.liked.isEmpty() && actions.unliked.isEmpty() && quickSave.saved.isEmpty())
    }

    @Test fun `a deck that removes keeps on right and removes on left, others like and skip`() {
        assertEquals(TriageChoice.Remove, choiceFor(SwipeDirection.Left, saveTarget = null, hideTag = null, removes = true))
        assertEquals(TriageChoice.Keep, choiceFor(SwipeDirection.Right, saveTarget = null, hideTag = null, removes = true))
        assertEquals(TriageChoice.Skip, choiceFor(SwipeDirection.Left, saveTarget = null, hideTag = null))
        assertEquals(TriageChoice.Like, choiceFor(SwipeDirection.Right, saveTarget = null, hideTag = null))
    }

    @Test fun `the deck asks a live feed for its next page near the end, once per page loaded`() = runTest {
        val live = FakeFeed(posts.take(7), canLoadMore = true)
        val deck = deck(live)
        assertEquals(0, live.pagesRequested)
        deck.decideCurrent(TriageChoice.Skip)
        deck.decideCurrent(TriageChoice.Skip)
        assertEquals(1, live.pagesRequested)
        deck.decideCurrent(TriageChoice.Skip)
        assertEquals(1, live.pagesRequested)
        live.emit(posts, canLoadMore = false)
        assertEquals(8, deck.state.value.posts.size)
        assertEquals(3, deck.state.value.index)
    }

    @Test fun `a page with no post the grid shows still lets the deck ask for the next one`() = runTest {
        val live = FakeFeed(posts.take(1), canLoadMore = true)
        val deck = deck(live)
        deck.decideCurrent(TriageChoice.Skip)
        assertEquals(1, live.pagesRequested)
        live.emit(posts.take(1), canLoadMore = true, loaded = 20)
        assertEquals(2, live.pagesRequested)
        live.emit(posts.take(1), canLoadMore = true, loaded = 20)
        assertEquals(2, live.pagesRequested)
        live.emit(posts.take(1), canLoadMore = false, loaded = 40)
        assertEquals(2, live.pagesRequested)
        assertTrue(deck.state.value.finished)
    }

    @Test fun `a failed page shows a retry instead of finishing, and Retry asks the feed again`() = runTest {
        val live = FakeFeed(posts.take(1), canLoadMore = true)
        val deck = deck(live)
        deck.decideCurrent(TriageChoice.Skip)
        assertEquals(1, live.pagesRequested)
        live.emit(posts.take(1), canLoadMore = true, pageFailed = true)
        assertTrue(deck.state.value.needsRetry)
        assertFalse(deck.state.value.finished)
        assertEquals(1, live.pagesRequested)

        deck.retryPage()
        assertEquals(1, live.retriesRequested)
        live.emit(posts.take(1), canLoadMore = true, loadingMore = true)
        deck.retryPage()
        assertEquals(1, live.retriesRequested)
        live.emit(posts.take(3), canLoadMore = false, loaded = 3)
        assertFalse(deck.state.value.needsRetry)
        assertEquals(3, deck.state.value.posts.size)
    }

    @Test fun `the deck never asks for a page while its feed is loading one`() = runTest {
        val live = FakeFeed(posts.take(7), canLoadMore = true)
        val deck = deck(live)
        deck.decideCurrent(TriageChoice.Skip)
        deck.decideCurrent(TriageChoice.Skip)
        assertEquals(1, live.pagesRequested)
        live.emit(posts.take(7), canLoadMore = true, loaded = 20, loadingMore = true)
        deck.decideCurrent(TriageChoice.Skip)
        assertEquals(1, live.pagesRequested)
        live.emit(posts.take(7), canLoadMore = true, loaded = 20)
        assertEquals(2, live.pagesRequested)
    }

    @Test fun `a repeated snapshot never duplicates posts and the deck ends when the feed has no more`() = runTest {
        val deck = deck()
        feed.emit(posts.take(3), canLoadMore = false)
        assertEquals(3, deck.state.value.posts.size)
        repeat(3) { deck.decideCurrent(TriageChoice.Skip) }
        assertNull(deck.state.value.current)
        assertTrue(deck.state.value.finished)
        deck.undo()
        assertEquals(2, deck.state.value.index)
        assertFalse(deck.state.value.finished)
    }

    @Test fun `past the last post a feed that can load more is not finished`() = runTest {
        val deck = deck(FakeFeed(posts.take(1), canLoadMore = true))
        deck.decideCurrent(TriageChoice.Skip)
        assertFalse(deck.state.value.finished)
    }

    @Test fun `like, undo and like again run in order, so the post ends liked`() = runTest {
        actions.hold = CompletableDeferred()
        actions.unlikeHold = CompletableDeferred()
        val deck = deck()
        deck.decideCurrent(TriageChoice.Like)
        deck.undo()
        deck.decideCurrent(TriageChoice.Like)
        actions.hold!!.complete(Unit)
        assertEquals(listOf(posts[0].id), actions.liked)
        assertEquals(emptyList<PostId>(), actions.unliked)
        actions.unlikeHold!!.complete(Unit)
        assertEquals(listOf(posts[0].id, posts[0].id), actions.liked)
        assertEquals(listOf(posts[0].id), actions.unliked)
        assertEquals(setOf(posts[0].id), actions.alreadyLiked)
        assertEquals(TriageNotice(TriageDecision.Liked(posts[0]), failed = false), deck.state.value.notice)
    }

    @Test fun `a decision made on a card that is no longer current does nothing`() = runTest {
        val deck = deck()
        deck.decideCurrent(TriageChoice.Skip)
        deck.decide(TriageChoice.Like, posts[0].id)
        assertEquals(1, deck.state.value.index)
        assertTrue(actions.liked.isEmpty())
    }

    @Test fun `a save after undoing one waits until the first save's removal has run`() = runTest {
        val removal = CompletableDeferred<Unit>()
        quickSave.undoHold = removal
        val deck = deck()
        deck.decideCurrent(TriageChoice.Save(ChosenCodex.Existing("codex-1", "Skies")))
        deck.undo()
        deck.decideCurrent(TriageChoice.Save(ChosenCodex.Existing("codex-2", "Clouds")))
        assertEquals(listOf("codex-1"), quickSave.saved.map { it.second })
        removal.complete(Unit)
        assertEquals(listOf("codex-1", "codex-2"), quickSave.saved.map { it.second })
        assertEquals(listOf(quickSave.records.first()), quickSave.undone)
    }

    @Test fun `closing the deck still runs a pending save and then the undo asked for it`() = runTest {
        quickSave.hold = CompletableDeferred()
        val deck = deck()
        deck.decideCurrent(TriageChoice.Save(ChosenCodex.Existing("codex-1", "Skies")))
        deck.undo()
        deck.close()
        quickSave.hold!!.complete(Unit)
        assertEquals(1, quickSave.records.size)
        assertEquals(quickSave.records, quickSave.undone)
    }

    @Test fun `closing a Codex deck still runs a pending removal and then the undo asked for it`() = runTest {
        val removal = FakeRemoval(removeHold = CompletableDeferred())
        val deck = removingDeck(removal)
        deck.decideCurrent(TriageChoice.Remove)
        deck.undo()
        deck.close()
        removal.removeHold!!.complete(Unit)
        assertEquals(listOf(posts[0].id), removal.removed)
        assertEquals(listOf(posts[0].id), removal.restored)
    }

    @Test fun `a decision is reported when made, before its write commits, and again as reverted by Undo`() = runTest {
        actions.hold = CompletableDeferred()
        val deck = deck()
        deck.decideCurrent(TriageChoice.Like)
        assertEquals(listOf(TriageDecisionEvent.Decided(TriageDecision.Liked(posts[0]))), events)
        deck.undo()
        actions.hold!!.complete(Unit)
        assertEquals(TriageDecisionEvent.Reverted(TriageDecision.Liked(posts[0])), events.last())
        assertEquals(2, events.size)
    }

    @Test fun `a failed write reports its decision reverted, whether or not newer swipes kept its card away`() = runTest {
        actions.failLike = true
        val deck = deck()
        deck.decideCurrent(TriageChoice.Like)
        assertEquals(TriageDecisionEvent.Reverted(TriageDecision.Liked(posts[0])), events.last())

        actions.hold = CompletableDeferred()
        deck.decideCurrent(TriageChoice.Like)
        deck.decideCurrent(TriageChoice.Skip)
        actions.hold!!.complete(Unit)
        assertEquals(
            listOf(
                TriageDecisionEvent.Decided(TriageDecision.Liked(posts[0])),
                TriageDecisionEvent.Reverted(TriageDecision.Liked(posts[0])),
                TriageDecisionEvent.Decided(TriageDecision.Liked(posts[0])),
                TriageDecisionEvent.Decided(TriageDecision.Skipped(posts[1])),
                TriageDecisionEvent.Reverted(TriageDecision.Liked(posts[0])),
            ),
            events,
        )
    }

    /** Decides for whichever card is current, as a swipe that finishes on its own card does. */
    private fun TriageDeck.decideCurrent(choice: TriageChoice) = decide(choice, checkNotNull(state.value.current).id)

    private class FakeFeed(initial: List<Post>, canLoadMore: Boolean) : TriageFeed {
        private val flow = MutableStateFlow(TriageFeedSnapshot(initial, canLoadMore))
        var pagesRequested = 0
        var retriesRequested = 0
        override val snapshots: Flow<TriageFeedSnapshot> = flow
        override fun loadNextPage() { pagesRequested++ }
        override fun retryPage() { retriesRequested++ }
        fun emit(
            posts: List<Post>,
            canLoadMore: Boolean,
            loaded: Int = posts.size,
            loadingMore: Boolean = false,
            pageFailed: Boolean = false,
        ) {
            flow.value = TriageFeedSnapshot(posts, canLoadMore, loaded, loadingMore, pageFailed)
        }
    }

    private class FakeRemoval(
        private val fail: Boolean = false,
        val removeHold: CompletableDeferred<Unit>? = null,
        val restoreHold: CompletableDeferred<Unit>? = null,
    ) : TriageRemoval {
        val removed = mutableListOf<PostId>()
        val restored = mutableListOf<PostId>()
        override suspend fun remove(post: Post): TriageReversal? {
            removeHold?.await()
            check(!fail)
            removed += post.id
            return {
                restoreHold?.await()
                restored += post.id
            }
        }
    }

    private class FakeActions : TriageActions {
        val alreadyLiked = mutableSetOf<PostId>()
        val liked = mutableListOf<PostId>()
        val unliked = mutableListOf<PostId>()
        val hidden = mutableListOf<Pair<SourceKey, String>>()
        var failLike = false
        var failHide = false
        var hold: CompletableDeferred<Unit>? = null
        var unlikeHold: CompletableDeferred<Unit>? = null

        /** The active profile, and the profile each write went to. */
        var profile = "main"
        val wroteTo = mutableListOf<String>()
        override val hiddenTags: Flow<Set<HiddenTag>> = flowOf(emptySet())
        override fun atDecision(): TriageActions {
            val bound = profile
            val actions = this
            return object : TriageActions by actions {
                override suspend fun like(post: Post) = actions.like(post).also { wroteTo += bound }
                override suspend fun hide(source: SourceKey, tag: String) = actions.hide(source, tag).also { wroteTo += bound }
            }
        }
        override suspend fun like(post: Post): TriageReversal? {
            hold?.await()
            check(!failLike)
            if (!alreadyLiked.add(post.id)) return null
            liked += post.id
            return {
                unlikeHold?.await()
                if (alreadyLiked.remove(post.id)) unliked += post.id
            }
        }
        override suspend fun hide(source: SourceKey, tag: String): TriageReversal? {
            check(!failHide)
            hidden += source to tag
            return { hidden.remove(source to tag) }
        }
    }

    private class FakeQuickSave : QuickSave {
        val saved = mutableListOf<Triple<PostId, String, Query?>>()
        val records = mutableListOf<QuickSaveRecord>()
        val undone = mutableListOf<QuickSaveRecord>()
        var fail = false
        var fromForYou = false
        var hold: CompletableDeferred<Unit>? = null
        var undoHold: CompletableDeferred<Unit>? = null
        override val lastQuickSave: StateFlow<QuickSaveRecord?> = MutableStateFlow(null)
        override suspend fun quickSave(post: Post, codexId: String, query: Query?, fromForYou: Boolean): QuickSaveRecord? {
            hold?.await()
            if (fail) return null
            this.fromForYou = fromForYou
            saved += Triple(post.id, codexId, query)
            return QuickSaveRecord(post, codexId, "Skies", inserted = 1, savedAtEpochMs = 0L).also { records += it }
        }
        override suspend fun saveToNew(post: Post, name: String, profileId: String, query: Query?, fromForYou: Boolean) =
            quickSave(post, "new:$name", query, fromForYou)
        override suspend fun undoQuickSave(record: QuickSaveRecord) {
            undoHold?.await()
            undone += record
        }
        override fun suggestions(post: Post, query: Query?, limit: Int): Flow<SaveSuggestions> = flowOf(SaveSuggestions.Empty)
    }
}
