package com.theoriacodex.app.triage

import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.app.codex.save
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.vision.PictureTags
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

sealed interface TriageDecisionEvent {
    val decision: TriageDecision

    data class Decided(override val decision: TriageDecision) : TriageDecisionEvent
    data class Reverted(override val decision: TriageDecision) : TriageDecisionEvent
}

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
    pictures: PictureTags = PictureTags.None,
    lead: Post? = null,
) {
    private val mutableState = MutableStateFlow(TriageDeckState(posts = listOfNotNull(lead), canLoadMore = lead != null))
    val state: StateFlow<TriageDeckState> = mutableState.asStateFlow()

    val removes: Boolean get() = removal != null

    val hiddenTags: Flow<Set<HiddenTag>> get() = actions.hiddenTags

    private class Work(val decision: TriageDecision) {
        var reversal: TriageReversal? = null
        var undone = false
    }

    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var latest: Work? = null
    private var loaded = 0
    private var pagedAtLoaded = -1
    private var feedLoading = false

    init {
        writeScope.launch {
            for (write in writes) runCatchingPreservingCancellation { write() }
        }
        scope.launch {
            feed.snapshots.collect { snapshot ->
                loaded = snapshot.loaded
                feedLoading = snapshot.loadingMore
                mutableState.update { deck ->
                    val fresh = snapshot.posts.associateBy { post -> post.id }
                    val known = deck.posts.mapTo(HashSet(), { post -> post.id })
                    val grown = deck.copy(
                        posts = deck.posts.map { post -> fresh[post.id] ?: post } + snapshot.posts.filter { post -> known.add(post.id) },
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
        scope.launch {
            pictures.library.collect {
                mutableState.update { deck ->
                    val told = deck.copy(pictures = pictures)
                    told.copy(index = told.shownFrom(told.index))
                }
                pageIfNearEnd()
            }
        }
    }

    fun decide(choice: TriageChoice, postId: PostId) {
        val post = mutableState.value.current?.takeIf { it.id == postId } ?: return
        val work = Work(decisionFor(choice, post))
        val actions = actions.atDecision()
        latest = work
        mutableState.update { deck ->
            val decision = work.decision
            val passedOver = if (decision is TriageDecision.Hid) deck.passedOverTags.with(post.id.source, decision.tag) else deck.passedOverTags
            val removed = if (decision is TriageDecision.Removed) deck.removed + post.id else deck.removed
            val notice = if (decision is TriageDecision.Kept) TriageNotice(decision, failed = false) else deck.notice
            val moved = deck.copy(last = decision, notice = notice, passedOverTags = passedOver, removed = removed, restored = null)
            moved.copy(index = moved.shownFrom(deck.index + 1))
        }
        onDecision(TriageDecisionEvent.Decided(work.decision))
        pageIfNearEnd()
        if (choice is TriageChoice.Skip || choice is TriageChoice.Keep) {
            work.reversal = NOTHING
        } else {
            writes.trySend {
                work.reversal = commit(actions, choice, post)
                settle(work)
            }
        }
    }

    fun undo() {
        val work = latest ?: return
        latest = null
        work.undone = true
        mutableState.update { deck -> deck.restoring(work.decision).copy(last = null, notice = null) }
        onDecision(TriageDecisionEvent.Reverted(work.decision))
        writes.trySend { work.reversal?.invoke() }
    }

    fun passOver(decidedElsewhere: Set<PostId>, forgetUndo: Boolean) {
        if (forgetUndo) latest = null
        mutableState.update { deck ->
            val told = deck.copy(
                elsewhere = deck.elsewhere + decidedElsewhere,
                last = if (forgetUndo) null else deck.last,
                restored = deck.restored.takeUnless { it in decidedElsewhere },
            )
            told.copy(index = told.shownFrom(told.index))
        }
        pageIfNearEnd()
    }

    fun retryPage() {
        if (feedLoading || !mutableState.value.pageFailed) return
        pagedAtLoaded = loaded
        feed.retryPage()
    }

    fun close() {
        writes.close()
    }

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

private fun Map<SourceKey, Set<String>>.with(source: SourceKey, tag: String): Map<SourceKey, Set<String>> =
    this + (source to this[source].orEmpty() + normalizeMatchToken(tag))

private fun Map<SourceKey, Set<String>>.without(source: SourceKey, tag: String): Map<SourceKey, Set<String>> {
    val left = this[source].orEmpty() - normalizeMatchToken(tag)
    return if (left.isEmpty()) this - source else this + (source to left)
}

private fun TriageDeckState.unwinding(decision: TriageDecision): TriageDeckState {
    val passedOver = if (decision is TriageDecision.Hid) passedOverTags.without(decision.post.id.source, decision.tag) else passedOverTags
    return copy(passedOverTags = passedOver, removed = removed - decision.post.id)
}

private fun TriageDeckState.restoring(decision: TriageDecision): TriageDeckState =
    unwinding(decision).copy(index = posts.indexOfFirst { it.id == decision.post.id }.coerceAtLeast(0), restored = decision.post.id)
