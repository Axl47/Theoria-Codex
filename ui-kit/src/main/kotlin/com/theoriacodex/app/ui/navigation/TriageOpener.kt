package com.theoriacodex.app.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.data.repository.ViewerStreamSource

/**
 * The feed a triage deck works through: [source] with [feedKey], the same key the Viewer's launch context
 * carries (a search's query hash, or `codex:<id>` for a Codex or Likes).
 */
data class TriageRequest(val source: ViewerStreamSource, val feedKey: String)

/** Opens the triage deck from a feed's menu. Read from [LocalTriageOpener]. */
fun interface TriageOpener {
    fun openTriage(request: TriageRequest)

    companion object {
        val None = TriageOpener { }
    }
}

val LocalTriageOpener = staticCompositionLocalOf { TriageOpener.None }
