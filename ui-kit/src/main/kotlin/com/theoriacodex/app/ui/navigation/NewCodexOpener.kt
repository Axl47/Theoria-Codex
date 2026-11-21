package com.theoriacodex.app.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.domain.model.Post

/**
 * Names a new Codex and saves [Post] into it, through the one Create Codex dialog the shell hosts. The lift's
 * New target and the Viewer dock's New and no-Codex save call it. Read from [LocalNewCodexOpener].
 */
fun interface NewCodexOpener {
    fun openNewCodex(post: Post)

    companion object {
        val None = NewCodexOpener { }
    }
}

val LocalNewCodexOpener = staticCompositionLocalOf { NewCodexOpener.None }
