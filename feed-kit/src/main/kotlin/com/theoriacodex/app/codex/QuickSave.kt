package com.theoriacodex.app.codex

import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/** A one-tap save that just happened, kept so its surface can confirm it in place and offer Undo. */
data class QuickSaveRecord(
    val post: Post,
    val codexId: String,
    val codexName: String,
    /** Posts the save added; 0 when the post was already in the Codex. */
    val inserted: Int,
    val savedAtEpochMs: Long,
) {
    /** Undo removes exactly what the save added, so it is offered only when the save added this one post. */
    val undoable: Boolean get() = inserted == 1
}

/** A Codex offered as a one-tap save target. */
data class SaveSuggestion(val codexId: String, val name: String, val cover: List<CodexCoverCandidate>)

/** The best Codices a post is not in yet, best first, and whether one of the profile's Codices holds it. */
data class SaveSuggestions(val targets: List<SaveSuggestion>, val inAnyCodex: Boolean) {
    companion object {
        val Empty = SaveSuggestions(emptyList(), inAnyCodex = false)
    }
}

/**
 * One-tap saving for the Viewer, feed grids and the triage deck, read from [LocalQuickSave].
 *
 * Saves go through the Save-to-Codex picker's path. Nothing here shows a snackbar: each surface confirms in
 * place from [lastQuickSave] and offers [undoLastQuickSave] while the record is [QuickSaveRecord.undoable]. A surface
 * that keeps the record of its own save undoes it with [undoQuickSave], so a later save elsewhere is never the one removed.
 */
interface QuickSave {
    /** The latest successful quick save, or null after it is undone. */
    val lastQuickSave: StateFlow<QuickSaveRecord?>

    /**
     * Saves [post] to Codex [codexId] and returns what it added, or null when the save failed. [query] is the
     * search the post came from, which ranks this Codex higher for that search's next posts. The save finishes
     * even if the caller is cancelled.
     */
    suspend fun quickSave(post: Post, codexId: String, query: Query? = null, fromForYou: Boolean = false): QuickSaveRecord?

    /** Removes what the last quick save added, when it is undoable; otherwise does nothing. */
    fun undoLastQuickSave()

    /**
     * Removes exactly what [record] added, when it is undoable, and clears [lastQuickSave] only while it still is
     * [record]; otherwise does nothing.
     */
    fun undoQuickSave(record: QuickSaveRecord)

    /** The top [limit] save targets for [post] (Codices it is not in yet), ranked by [rankSaveTargets]. */
    fun suggestions(post: Post, query: Query? = null, limit: Int = DEFAULT_SUGGESTIONS): Flow<SaveSuggestions>

    companion object {
        const val DEFAULT_SUGGESTIONS = 4
    }
}

/** Does nothing; previews and tests outside the app shell see no targets. */
private object NoQuickSave : QuickSave {
    override val lastQuickSave: StateFlow<QuickSaveRecord?> = MutableStateFlow<QuickSaveRecord?>(null).asStateFlow()
    override suspend fun quickSave(post: Post, codexId: String, query: Query?, fromForYou: Boolean): QuickSaveRecord? = null
    override fun undoLastQuickSave() = Unit
    override fun undoQuickSave(record: QuickSaveRecord) = Unit
    override fun suggestions(post: Post, query: Query?, limit: Int): Flow<SaveSuggestions> = flowOf(SaveSuggestions.Empty)
}

val LocalQuickSave = staticCompositionLocalOf<QuickSave> { NoQuickSave }
