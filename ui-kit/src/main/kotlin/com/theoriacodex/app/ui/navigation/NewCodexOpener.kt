package com.theoriacodex.app.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.domain.model.Post

/** Receives the Codex a picker or the Create Codex dialog chose, for a caller that saves and confirms it itself. */
typealias CodexChosen = (codexId: String, name: String) -> Unit

/**
 * Names a new Codex and saves [Post] into it, through the one Create Codex dialog the shell hosts. Every save menu's
 * New target and the Viewer dock's no-Codex save call it. Read from [LocalNewCodexOpener].
 */
fun interface NewCodexOpener {
    /**
     * Asks for a name, creates the Codex, and saves [post] into it; with [onCreated], it saves nothing and hands the
     * new Codex's id and name there instead, for a caller that saves and confirms it itself, as the lift and the triage
     * deck do so their Undo covers it.
     */
    fun openNewCodex(post: Post, onCreated: CodexChosen?)

    companion object {
        val None = NewCodexOpener { _, _ -> }
    }
}

val LocalNewCodexOpener = staticCompositionLocalOf { NewCodexOpener.None }
