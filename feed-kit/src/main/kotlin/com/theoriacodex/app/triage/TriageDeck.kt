package com.theoriacodex.app.triage

import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.app.codex.save
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What happened to a decision, for an owner that steers by them. [Decided] fires when a card is decided, before its
 * write commits; [Reverted] when that decision no longer stands: Undo took it back, or its write failed.
 */
sealed interface TriageDecisionEvent {
    val decision: TriageDecision

    data class Decided(override val decision: TriageDecision) : TriageDecisionEvent
    data class Reverted(override val decision: TriageDecision) : TriageDecisionEvent
}

/**
 * One triage deck: the origin feed's posts, the position in them, and what each swipe does. The screen reads [state],
 * calls [decide] when a card commits and [undo] for the one level of undo. A feed that grows is appended to, never
 * reordered, so the position stays valid. [scope] collects [feed] for as long as it is active; the owner cancels it.
 *
 * A decision moves the card on at once. Its write, and any reversal Undo asks for, run one at a time in [writeScope], in
 * the order they were asked for, so a reversal always follows its write and a later write follows both. [writeScope]
 * outlives the deck, so writes and reversals already asked for still run, in order, after [close].
 * [TriageDeckState.notice] confirms a decision only once its write commits; a failed write brings its card back.
 * [query] and [fromForYou] describe the origin feed for save ranking. With a [removal], left takes the post out of the
 * deck's Codex instead of skipping it, through the same ordered writes, and right keeps it instead of liking it.
 * The deck asks [feed] for a page once [pageAhead] or fewer cards remain. [onDecision] hears every
 * [TriageDecisionEvent], on the thread that caused it (a failed write's on [writeScope]'s), outside state updates.
 * [ownerHidden] is what the deck's owner hides besides the deck's own swipes: tags by source, as
 * [normalizeMatchToken] spells them. Cards carrying one are passed over for as long as it is there.
 */
class TriageDeck(
    scope: CoroutineScope,
    private val feed: TriageFeed,
    private val actions: TriageActions,
    private val quickSave: QuickSave,
    private val query: Query?,
    private val fromForYou: Boolean,
    writeScope: CoroutineScope,
    private val removal: TriageRemoval? = null,
    private val pageAhead: Int = 5,
    private val onDecision: (TriageDecisionEvent) -> Unit = {},
    ownerHidden: Flow<Map<SourceKey, Set<String>>> = emptyFlow(),
) {
    private val mutableState = MutableStateFlow(TriageDeckState())
    val state: StateFlow<TriageDeckState> = mutableState.asStateFlow()

    /** Whether a left swipe removes the post from the deck's Codex rather than skipping it, and a right one keeps it. */
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
    private var feedLoading = false

    init {
        // Ends once the deck has closed and the queue has drained, so it holds the deck no longer than its writes.
        writeScope.launch {
            for (write in writes) runCatchingPreservingCancellation { write() }
        }
        scope.launch {
            feed.snapshots.collect { snapshot ->
                loaded = snapshot.loaded
                feedLoading = snapshot.loadingMore
                mutableState.update { deck ->
                    val known = deck.posts.mapTo(HashSet(), { post -> post.id })
                    val grown = deck.copy(
                        posts = deck.posts + snapshot.posts.filter { post -> known.add(post.id) },
                        canLoadMore = snapshot.canLoadMore,
                        pageFailed = snapshot.pageFailed,
                        loaded = true,
                    )
                    grown.copy(index = grown.shownFrom(grown.index))
                }
                pageIfNearEnd()
            }
        }
        scope.launch {
            ownerHidden.collect { hidden ->
                mutableState.update { deck ->
                    val told = deck.copy(ownerHiddenTags = hidden)
                    told.copy(index = told.shownFrom(told.index))
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
        // Read now, so a like or a hide goes to the profile that was active when the card was decided.
        val actions = actions.atDecision()
        latest = work
        mutableState.update { deck ->
            val decision = work.decision
            val passedOver = if (decision is TriageDecision.Hid) deck.passedOverTags.with(post.id.source, decision.tag) else deck.passedOverTags
            val removed = if (decision is TriageDecision.Removed) deck.removed + post.id else deck.removed
            // A keep writes nothing, so it is confirmed at once.
            val notice = if (decision is TriageDecision.Kept) TriageNotice(decision, failed = false) else deck.notice
            val moved = deck.copy(last = decision, notice = notice, passedOverTags = passedOver, removed = removed, restored = null)
            moved.copy(index = moved.shownFrom(deck.index + 1))
        }
        onDecision(TriageDecisionEvent.Decided(work.decision))
        pageIfNearEnd()
        // A skip or a keep writes nothing, so it has nothing to wait for.
        if (choice is TriageChoice.Skip || choice is TriageChoice.Keep) {
            work.reversal = NOTHING
        } else {
            writes.trySend {
                work.reversal = commit(actions, choice, post)
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
        onDecision(TriageDecisionEvent.Reverted(work.decision))
        writes.trySend { work.reversal?.invoke() }
    }

    /**
     * Asks the feed again for the page that failed. Does nothing while the feed is already loading one, since some
     * feeds start over when asked mid-request.
     */
    fun retryPage() {
        if (feedLoading || !mutableState.value.pageFailed) return
        pagedAtLoaded = loaded
        feed.retryPage()
    }

    /** Takes no more writes; those already asked for still run, in order, in the write scope. */
    fun close() {
        writes.close()
    }

    /** Runs [choice]'s write for [post] through [actions]; returns how to reverse exactly what it did, or null when it failed. */
    private suspend fun commit(actions: TriageActions, choice: TriageChoice, post: Post): TriageReversal? = runCatchingPreservingCancellation<TriageReversal> {
        when (choice) {
            TriageChoice.Skip, TriageChoice.Keep -> NOTHING
            TriageChoice.Remove -> checkNotNull(removal) { "This deck does not remove" }.remove(post) ?: NOTHING
            TriageChoice.Like -> actions.like(post) ?: NOTHING
            is TriageChoice.Save -> {
                val record = quickSave.save(post, choice.codex, query, fromForYou) ?: error("The save failed")
                suspend { quickSave.undoQuickSave(record) }
            }
            is TriageChoice.Hide -> actions.hide(post.id.source, choice.tag) ?: NOTHING
        }
    }.getOrNull()

    /**
     * Confirms [work]'s decision once its write has run, unless it was undone. A failure brings the card back while
     * it is still the latest decision; after newer swipes it only says so, and the deck stays where it is. Either way
     * a failed decision is reverted for [onDecision].
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
        if (failed) onDecision(TriageDecisionEvent.Reverted(work.decision))
    }

    /** Asks for a page when few cards remain, once per page loaded and never while the feed is loading one. */
    private fun pageIfNearEnd() {
        val deck = mutableState.value
        if (feedLoading || deck.pageFailed || !deck.canLoadMore || loaded == pagedAtLoaded || deck.posts.size - deck.index > pageAhead) return
        pagedAtLoaded = loaded
        feed.loadNextPage()
    }

    private companion object {
        val NOTHING: TriageReversal = {}
    }
}

private fun decisionFor(choice: TriageChoice, post: Post): TriageDecision = when (choice) {
    TriageChoice.Like -> TriageDecision.Liked(post)
    TriageChoice.Skip -> TriageDecision.Skipped(post)
    TriageChoice.Keep -> TriageDecision.Kept(post)
    TriageChoice.Remove -> TriageDecision.Removed(post)
    is TriageChoice.Save -> TriageDecision.Saved(post, choice.codex.name)
    is TriageChoice.Hide -> TriageDecision.Hid(post, choice.tag)
}

/** These tags with [tag] hidden on [source]. */
private fun Map<SourceKey, Set<String>>.with(source: SourceKey, tag: String): Map<SourceKey, Set<String>> =
    this + (source to this[source].orEmpty() + normalizeMatchToken(tag))

private fun Map<SourceKey, Set<String>>.without(source: SourceKey, tag: String): Map<SourceKey, Set<String>> {
    val left = this[source].orEmpty() - normalizeMatchToken(tag)
    return if (left.isEmpty()) this - source else this + (source to left)
}

/** The deck with what [decision] took out of it shown again: the tag it hid, the post it removed. */
private fun TriageDeckState.unwinding(decision: TriageDecision): TriageDeckState {
    val passedOver = if (decision is TriageDecision.Hid) passedOverTags.without(decision.post.id.source, decision.tag) else passedOverTags
    return copy(passedOverTags = passedOver, removed = removed - decision.post.id)
}

/** The deck with [decision] unwound and its card current again, whatever is hidden meanwhile. */
private fun TriageDeckState.restoring(decision: TriageDecision): TriageDeckState =
    unwinding(decision).copy(index = posts.indexOfFirst { it.id == decision.post.id }.coerceAtLeast(0), restored = decision.post.id)
