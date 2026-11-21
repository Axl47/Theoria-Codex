package com.theoriacodex.app.triage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.tags.normalizeMatchToken
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
 * A decision moves the card on at once and its write runs in this owner's scope. [TriageDeckState.notice] confirms
 * it only once the write commits; a failed write brings its card back. Undo works straight away: a reversal waits
 * behind a write still running, and removes exactly what that write added. [query] and [fromForYou] describe the
 * origin feed for save ranking.
 */
class TriageDeckViewModel(
    private val feed: TriageFeed,
    private val actions: TriageActions,
    private val quickSave: QuickSave,
    private val query: Query?,
    private val fromForYou: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TriageDeckState())
    val state: StateFlow<TriageDeckState> = mutableState.asStateFlow()

    /** The active profile's hidden tags, which the deck does not offer to hide again. */
    val hiddenTags: Flow<Set<HiddenTag>> get() = actions.hiddenTags

    /** The write behind [TriageDeckState.last]; it resolves to how to reverse it, or null when it failed. */
    private class Work(val decision: TriageDecision, val reversal: Deferred<Reversal?>) {
        var undone = false
    }

    private var latest: Work? = null
    private var pagedAtSize = -1

    init {
        viewModelScope.launch {
            feed.snapshots.collect { snapshot ->
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

    /** Applies [choice] to the current card and moves to the next. */
    fun decide(choice: TriageChoice) {
        val post = mutableState.value.current ?: return
        val decision = decisionFor(choice, post)
        val work = Work(decision, viewModelScope.async { commit(choice, post) })
        latest = work
        mutableState.update { deck ->
            val passedOver = if (decision is TriageDecision.Hid) deck.passedOverTags + normalizeMatchToken(decision.tag) else deck.passedOverTags
            val moved = deck.copy(last = decision, passedOverTags = passedOver)
            moved.copy(index = moved.shownFrom(deck.index + 1))
        }
        pageIfNearEnd()
        viewModelScope.launch { settle(work, work.reversal.await()) }
    }

    /** Brings the last decided card back and reverses what its decision did, after its write if that still runs. */
    fun undo() {
        val work = latest ?: return
        latest = null
        work.undone = true
        mutableState.update { deck -> deck.restoring(work.decision).copy(last = null, notice = null) }
        viewModelScope.launch { work.reversal.await()?.invoke() }
    }

    /** Runs [choice]'s write for [post]; returns how to reverse exactly what it did, or null when it failed. */
    private suspend fun commit(choice: TriageChoice, post: Post): Reversal? = runCatchingPreservingCancellation<Reversal> {
        when (choice) {
            TriageChoice.Skip -> NOTHING
            TriageChoice.Like -> if (actions.like(post)) suspend { actions.unlike(post) } else NOTHING
            is TriageChoice.Save -> {
                val record = quickSave.quickSave(post, choice.codexId, query, fromForYou) ?: error("The save failed")
                suspend { quickSave.undoQuickSave(record) }
            }
            is TriageChoice.Hide ->
                if (actions.hide(post.id.source, choice.tag)) suspend { actions.unhide(post.id.source, choice.tag) } else NOTHING
        }
    }.getOrNull()

    /**
     * Confirms [work]'s decision once its write is known, unless it was undone. A failure brings the card back while
     * it is still the latest decision; after newer swipes it only says so, and the deck stays where it is.
     */
    private fun settle(work: Work, reversal: Reversal?) {
        if (work.undone) return
        val failed = reversal == null
        val stillLatest = latest === work
        if (failed && stillLatest) latest = null
        mutableState.update { deck ->
            val notice = TriageNotice(work.decision, failed)
            when {
                !failed -> deck.copy(notice = notice)
                stillLatest -> deck.restoring(work.decision).copy(last = null, notice = notice)
                else -> deck.copy(notice = notice, passedOverTags = deck.passedOverTags - work.decision.passedOverTag())
            }
        }
    }

    private fun pageIfNearEnd() {
        val deck = mutableState.value
        if (!deck.canLoadMore || deck.posts.size == pagedAtSize || deck.posts.size - deck.index > PAGE_AHEAD) return
        pagedAtSize = deck.posts.size
        feed.loadNextPage()
    }

    private companion object {
        /** The deck asks its feed for more when this many cards or fewer remain. */
        const val PAGE_AHEAD = 5
        val NOTHING: Reversal = {}
    }
}

/** Reverses one committed decision. */
private typealias Reversal = suspend () -> Unit

private fun decisionFor(choice: TriageChoice, post: Post): TriageDecision = when (choice) {
    TriageChoice.Like -> TriageDecision.Liked(post)
    TriageChoice.Skip -> TriageDecision.Skipped(post)
    is TriageChoice.Save -> TriageDecision.Saved(post, choice.codexName)
    is TriageChoice.Hide -> TriageDecision.Hid(post, choice.tag)
}

/** The tag [this] decision hid from the deck, as the deck spells it; none for other decisions. */
private fun TriageDecision.passedOverTag(): Set<String> =
    if (this is TriageDecision.Hid) setOf(normalizeMatchToken(tag)) else emptySet()

/** The deck with [decision]'s card current again and the tag it hid shown again. */
private fun TriageDeckState.restoring(decision: TriageDecision): TriageDeckState {
    val shown = copy(passedOverTags = passedOverTags - decision.passedOverTag())
    return shown.copy(index = posts.indexOfFirst { it.id == decision.post.id }.coerceAtLeast(0))
}
