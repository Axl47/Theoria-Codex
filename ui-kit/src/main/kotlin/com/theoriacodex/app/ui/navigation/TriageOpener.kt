package com.theoriacodex.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.data.repository.ViewerStreamSource

/**
 * The feed a triage deck works through: [source] with [feedKey], the same key the Viewer's launch context
 * carries (a search's query hash, or `codex:<id>` for a Codex or Likes), and what a left swipe does there.
 */
data class TriageRequest(
    val source: ViewerStreamSource,
    val feedKey: String,
    val mode: TriageMode = TriageMode.Browse,
) {
    /** The Codex a [ViewerStreamSource.CODEX] request names, from its `codex:<id>` feed key. */
    val codexId: String?
        get() = feedKey.takeIf { source == ViewerStreamSource.CODEX && it.startsWith(CODEX_FEED_PREFIX) }
            ?.removePrefix(CODEX_FEED_PREFIX)

    companion object {
        /** The deck over Codex [codexId]'s posts. */
        fun codex(codexId: String, mode: TriageMode = TriageMode.Browse) =
            TriageRequest(ViewerStreamSource.CODEX, CODEX_FEED_PREFIX + codexId, mode)
    }
}

/** What a left swipe in the deck does. */
sealed interface TriageMode {
    /** Left passes over the card. */
    data object Browse : TriageMode

    /**
     * Left takes the card out of the Codex the request names. With [alsoIn], the deck holds only that Codex's posts
     * that one of those Codices also holds: what cleaning the Codex against them would remove.
     */
    data class RemoveFromCodex(val alsoIn: Set<String> = emptySet()) : TriageMode
}

/** Opens the triage deck from a feed's menu. Read from [LocalTriageOpener]. */
fun interface TriageOpener {
    fun openTriage(request: TriageRequest)

    companion object {
        val None = TriageOpener { }
    }
}

val LocalTriageOpener = staticCompositionLocalOf { TriageOpener.None }

/**
 * The action for a feed's filter sheet ([com.theoriacodex.app.ui.components.FeedFilterSheet]'s `onTriage`): opens
 * the deck over [feedKey] from [source] in [mode]. Null, so the sheet offers no Triage, when there is no [feedKey]
 * or no [hasPosts] to work through, or when nothing in the app provides a [LocalTriageOpener].
 */
@Composable
fun rememberTriageAction(
    source: ViewerStreamSource,
    feedKey: String?,
    hasPosts: Boolean,
    mode: TriageMode = TriageMode.Browse,
): (() -> Unit)? {
    val opener = LocalTriageOpener.current
    return remember(opener, source, feedKey, hasPosts, mode) {
        if (opener === TriageOpener.None || feedKey.isNullOrBlank() || !hasPosts) null
        else ({ opener.openTriage(TriageRequest(source, feedKey, mode)) })
    }
}

private const val CODEX_FEED_PREFIX = "codex:"
