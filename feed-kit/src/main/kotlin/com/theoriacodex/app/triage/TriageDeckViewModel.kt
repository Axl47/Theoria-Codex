package com.theoriacodex.app.triage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.tags.normalizeMatchToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One triage deck: the origin feed's posts, the position in them, and what each swipe does. The screen reads
 * [state], calls [decide] when a card commits and [undo] for the one level of undo. Rotation keeps it; a feed
 * that grows is appended to, never reordered, so the position stays valid.
 *
 * A decision moves the card on at once. Its write, and any reversal Undo asks for, run one at a time in [writeScope], in
 * the order they were asked for, so a reversal always follows its write and a later write follows both. [writeScope]
 * outlives the deck, so writes and reversals already asked for still run, in order, after the deck closes.
 * [TriageDeckState.notice] confirms a decision only once its write commits; a failed write brings its card back.
 * [query] and [fromForYou] describe the origin feed for save ranking. With a [removal], left takes the post out of the
 * deck's Codex instead of skipping it, through the same ordered writes.
 */
class TriageDeckViewModel(
    private val feed: TriageFeed,
    private val actions: TriageActions,
    private val quickSave: QuickSave,
    private val query: Query?,
    private val fromForYou: Boolean,
    writeScope: CoroutineScope,
    private val removal: TriageRemoval? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TriageDeckState())
    val state: StateFlow<TriageDeckState> = mutableState.asStateFlow()

    /** Whether a left swipe removes the post from the deck's Codex rather than skipping it. */
    val removes: Boolean get() = removal != null

    /** The active profile's hidden tags, which the deck does not offer to hide again. */
    val hiddenTags: Flow<Set<HiddenTag>> get() = actions.hiddenTags

    /** The write behind [TriageDeckState.last], and once it has run, how to reverse it (null when it failed). */
    private class Work(val decision: TriageDecision) {
        var reversal: TriageReversal? = null
        var undone = false
    }

    /** Every write and reversal, run one at a time in the order they were asked for. */
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var latest: Work? = null
    /** [TriageFeedSnapshot.loaded] of the latest snapshot, and what it was when the deck last asked for a page. */
    private var loaded = 0
    private var pagedAtLoaded = -1

    init {
        // Ends once the deck has closed and the queue has drained, so it holds the deck no longer than its writes.
        writeScope.launch {
            for (write in writes) runCatchingPreservingCancellation { write() }
        }
        viewModelScope.launch {
            feed.snapshots.collect { snapshot ->
                loaded = snapshot.loaded
                mutableState.update { deck ->
                    val known = deck.posts.mapTo(HashSet(), { post -> post.id })
                    val grown = deck.copy(
                        posts = deck.posts + snapshot.posts.filter { post -> known.add(post.id) },
                        canLoadMore = snapshot.canLoadMore,
                        loaded = true,
                    )
                    grown.copy(index = grown.shownFrom(grown.index))
                }
                pageIfNearEnd()
            }
        }
    }

    /**
     * Applies [choice] to card [postId] and moves to the next. Does nothing unless that card is the current one, so a
     * swipe that finishes late never decides for the card that replaced it.
     */
    fun decide(choice: TriageChoice, postId: PostId) {
        val post = mutableState.value.current?.takeIf { it.id == postId } ?: return
        val work = Work(decisionFor(choice, post))
        latest = work
        mutableState.update { deck ->
            val decision = work.decision
            val passedOver = if (decision is TriageDecision.Hid) deck.passedOverTags + normalizeMatchToken(decision.tag) else deck.passedOverTags
            val removed = if (decision is TriageDecision.Removed) deck.removed + post.id else deck.removed
            val moved = deck.copy(last = decision, passedOverTags = passedOver, removed = removed)
            moved.copy(index = moved.shownFrom(deck.index + 1))
        }
        pageIfNearEnd()
        // A skip writes nothing, so it has nothing to wait for or confirm.
        if (choice is TriageChoice.Skip) {
            work.reversal = NOTHING
        } else {
            writes.trySend {
                work.reversal = commit(choice, post)
                settle(work)
            }
        }
    }

    /** Brings the last decided card back and reverses exactly what its decision did, once its write has run. */
    fun undo() {
        val work = latest ?: return
        latest = null
        work.undone = true
        mutableState.update { deck -> deck.restoring(work.decision).copy(last = null, notice = null) }
        writes.trySend { work.reversal?.invoke() }
    }

    /** Runs [choice]'s write for [post]; returns how to reverse exactly what it did, or null when it failed. */
    private suspend fun commit(choice: TriageChoice, post: Post): TriageReversal? = runCatchingPreservingCancellation<TriageReversal> {
        when (choice) {
            TriageChoice.Skip -> NOTHING
            TriageChoice.Remove -> checkNotNull(removal) { "This deck does not remove" }.remove(post) ?: NOTHING
            TriageChoice.Like -> actions.like(post) ?: NOTHING
            is TriageChoice.Save -> {
                val record = quickSave.quickSave(post, choice.codexId, query, fromForYou) ?: error("The save failed")
                suspend { quickSave.undoQuickSave(record) }
            }
            is TriageChoice.Hide -> actions.hide(post.id.source, choice.tag) ?: NOTHING
        }
    }.getOrNull()

    /**
     * Confirms [work]'s decision once its write has run, unless it was undone. A failure brings the card back while
     * it is still the latest decision; after newer swipes it only says so, and the deck stays where it is.
     */
    private fun settle(work: Work) {
        if (work.undone) return
        val failed = work.reversal == null
        val stillLatest = latest === work
        if (failed && stillLatest) latest = null
        mutableState.update { deck ->
            val notice = TriageNotice(work.decision, failed)
            when {
                !failed -> deck.copy(notice = notice)
                stillLatest -> deck.restoring(work.decision).copy(last = null, notice = notice)
                else -> deck.unwinding(work.decision).copy(notice = notice)
            }
        }
    }

    override fun onCleared() {
        writes.close()
    }

    private fun pageIfNearEnd() {
        val deck = mutableState.value
        if (!deck.canLoadMore || loaded == pagedAtLoaded || deck.posts.size - deck.index > PAGE_AHEAD) return
        pagedAtLoaded = loaded
        feed.loadNextPage()
    }

    private companion object {
        /** The deck asks its feed for more when this many cards or fewer remain. */
        const val PAGE_AHEAD = 5
        val NOTHING: TriageReversal = {}
    }
}

private fun decisionFor(choice: TriageChoice, post: Post): TriageDecision = when (choice) {
    TriageChoice.Like -> TriageDecision.Liked(post)
    TriageChoice.Skip -> TriageDecision.Skipped(post)
    TriageChoice.Remove -> TriageDecision.Removed(post)
    is TriageChoice.Save -> TriageDecision.Saved(post, choice.codexName)
    is TriageChoice.Hide -> TriageDecision.Hid(post, choice.tag)
}

/** The tag [this] decision hid from the deck, as the deck spells it; none for other decisions. */
private fun TriageDecision.passedOverTag(): Set<String> =
    if (this is TriageDecision.Hid) setOf(normalizeMatchToken(tag)) else emptySet()

/** The deck with what [decision] took out of it shown again: the tag it hid, the post it removed. */
private fun TriageDeckState.unwinding(decision: TriageDecision): TriageDeckState =
    copy(passedOverTags = passedOverTags - decision.passedOverTag(), removed = removed - decision.post.id)

/** The deck with [decision] unwound and its card current again. */
private fun TriageDeckState.restoring(decision: TriageDecision): TriageDeckState =
    unwinding(decision).copy(index = posts.indexOfFirst { it.id == decision.post.id }.coerceAtLeast(0))
