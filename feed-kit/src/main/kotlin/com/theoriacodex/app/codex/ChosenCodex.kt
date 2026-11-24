package com.theoriacodex.app.codex

import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query

sealed interface ChosenCodex {
    val name: String

    data class Existing(val codexId: String, override val name: String) : ChosenCodex

    data class New(override val name: String, val profileId: String) : ChosenCodex
}

typealias CodexChosen = (ChosenCodex) -> Unit

suspend fun QuickSave.save(post: Post, codex: ChosenCodex, query: Query? = null, fromForYou: Boolean = false): QuickSaveRecord? =
    when (codex) {
        is ChosenCodex.Existing -> quickSave(post, codex.codexId, query, fromForYou)
        is ChosenCodex.New -> saveToNew(post, codex.name, codex.profileId, query, fromForYou)
    }

fun interface NewCodexOpener {
    fun openNewCodex(onChosen: suspend (ChosenCodex.New) -> Unit)

    companion object {
        val None = NewCodexOpener { }
    }
}

val LocalNewCodexOpener = staticCompositionLocalOf { NewCodexOpener.None }
