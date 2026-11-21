package com.theoriacodex.app.triage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
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
 * Saves and hides run in this owner's scope after the card has moved on; their decision becomes undoable once
 * they settle. [query] and [fromForYou] describe the origin feed for save ranking.
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

    private var pagedAtSize = -1

    init {
        viewModelScope.launch {
            feed.snapshots.collect { snapshot ->
                mutableState.update { deck ->
                    val known = deck.posts.mapTo(HashSet(), { post -> post.id })
                    deck.copy(
                        posts = deck.posts + snapshot.posts.filter { post -> known.add(post.id) },
                        canLoadMore = snapshot.canLoadMore,
                        loaded = true,
                    )
                }
                pageIfNearEnd()
            }
        }
    }

    /** Applies [choice] to the current card and moves to the next. */
    fun decide(choice: TriageChoice) {
        val post = mutableState.value.current ?: return
        mutableState.update { deck -> deck.copy(index = deck.index + 1, last = pending(choice, post)) }
        pageIfNearEnd()
        when (choice) {
            TriageChoice.Skip -> Unit
            TriageChoice.Like -> viewModelScope.launch {
                val newLike = runCatchingPreservingCancellation { actions.like(post) }.getOrDefault(false)
                settle(post.id) { TriageDecision.Liked(post, newLike) }
            }
            is TriageChoice.Save -> viewModelScope.launch {
                val record = quickSave.quickSave(post, choice.codexId, query, fromForYou)
                if (record == null) restore(post.id) else settle(post.id) { TriageDecision.Saved(post, choice.codexName, record) }
            }
            is TriageChoice.Hide -> viewModelScope.launch {
                val added = runCatchingPreservingCancellation { actions.hide(post.id.source, choice.tag) }.getOrDefault(false)
                settle(post.id) { TriageDecision.Hid(post, choice.tag, added) }
            }
        }
    }

    /** Brings the last decided card back and reverses what its decision did. Does nothing while it is still settling. */
    fun undo() {
        val decision = mutableState.value.last?.takeIf(TriageDecision::undoable) ?: return
        val post = decision.post
        when (decision) {
            is TriageDecision.Liked -> if (decision.newLike == true) actions.unlike(post)
            is TriageDecision.Saved -> quickSave.undoLastQuickSave()
            is TriageDecision.Hid -> if (decision.added == true) {
                viewModelScope.launch { actions.unhide(post.id.source, decision.tag) }
            }
            is TriageDecision.Skipped -> Unit
        }
        mutableState.update { deck ->
            deck.copy(index = deck.posts.indexOfFirst { it.id == post.id }.coerceAtLeast(0), last = null)
        }
    }

    private fun pending(choice: TriageChoice, post: Post): TriageDecision = when (choice) {
        TriageChoice.Like -> TriageDecision.Liked(post)
        TriageChoice.Skip -> TriageDecision.Skipped(post)
        is TriageChoice.Save -> TriageDecision.Saved(post, choice.codexName)
        is TriageChoice.Hide -> TriageDecision.Hid(post, choice.tag)
    }

    /** Replaces the decision for [postId] with its settled form, unless a newer swipe has replaced it. */
    private fun settle(postId: PostId, settled: () -> TriageDecision) {
        mutableState.update { deck -> if (deck.last?.post?.id == postId) deck.copy(last = settled()) else deck }
    }

    /** A save that failed leaves the card where it was, so nothing is lost silently. */
    private fun restore(postId: PostId) {
        mutableState.update { deck ->
            val index = deck.posts.indexOfFirst { it.id == postId }
            if (deck.last?.post?.id == postId && index >= 0) deck.copy(index = index, last = null) else deck
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
    }
}
