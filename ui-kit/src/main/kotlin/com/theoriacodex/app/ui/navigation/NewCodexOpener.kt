package com.theoriacodex.app.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.domain.model.Post

/**
 * Names a new Codex and saves [Post] into it, through the one Create Codex dialog the shell hosts. Every save menu's
 * New target and the Viewer dock's no-Codex save call it. Read from [LocalNewCodexOpener].
 */
fun interface NewCodexOpener {
    /**
     * Asks for a name, creates the Codex, and saves [post] into it; with [onCreated], it saves nothing and hands the
     * new Codex's id and name there instead, for a caller that saves itself, as the triage deck does so Undo covers it.
     */
    fun openNewCodex(post: Post, onCreated: ((codexId: String, name: String) -> Unit)?)

    companion object {
        val None = NewCodexOpener { _, _ -> }
    }
}

val LocalNewCodexOpener = staticCompositionLocalOf { NewCodexOpener.None }
