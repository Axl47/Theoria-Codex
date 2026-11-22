package com.theoriacodex.app.codex

import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query

/**
 * A Codex the user picked to save into: one that exists, or a new one that the save creates. The picker and the Create
 * Codex dialog only choose; whoever asked saves through [QuickSave.save], so a Codex is created only when a save runs
 * and Undo can take it away again.
 */
sealed interface ChosenCodex {
    val name: String

    data class Existing(val codexId: String, override val name: String) : ChosenCodex

    /** A Codex to create in profile [profileId], named [name]. */
    data class New(override val name: String, val profileId: String) : ChosenCodex
}

/** Receives the Codex a picker or the Create Codex dialog chose. */
typealias CodexChosen = (ChosenCodex) -> Unit

/** Saves [post] to [codex], creating it first when it is new; see [QuickSave.quickSave] and [QuickSave.saveToNew]. */
suspend fun QuickSave.save(post: Post, codex: ChosenCodex, query: Query? = null, fromForYou: Boolean = false): QuickSaveRecord? =
    when (codex) {
        is ChosenCodex.Existing -> quickSave(post, codex.codexId, query, fromForYou)
        is ChosenCodex.New -> saveToNew(post, codex.name, codex.profileId, query, fromForYou)
    }

/**
 * Asks for a new Codex's name through the one Create Codex dialog the shell hosts, then runs [onChosen] with the new
 * Codex to save into, in the shell's scope, so it finishes even when the screen that asked has gone. Every save menu's
 * New target and the Viewer dock's no-Codex save call it. Read from [LocalNewCodexOpener].
 */
fun interface NewCodexOpener {
    fun openNewCodex(onChosen: suspend (ChosenCodex.New) -> Unit)

    companion object {
        val None = NewCodexOpener { }
    }
}

val LocalNewCodexOpener = staticCompositionLocalOf { NewCodexOpener.None }
