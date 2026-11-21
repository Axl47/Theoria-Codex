package com.theoriacodex.app.codex

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.theoriacodex.app.media.CodexOfflineRing
import com.theoriacodex.app.media.LocalCodexOfflineSheetOpener
import com.theoriacodex.app.media.LocalCodexOfflineStates

/**
 * Codex [codexId]'s offline ring, drawn only while the Codex is kept offline. A full ring opens the Library's
 * offline sheet on its row; an incomplete or failed one tops the copy up through [onTopUp], which skips what is
 * already kept. Without [onTopUp] every tap opens the sheet.
 */
@Composable
internal fun CodexOfflineRingFor(codexId: String, onTopUp: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state = LocalCodexOfflineStates.current[codexId] ?: return
    val sheet = LocalCodexOfflineSheetOpener.current
    CodexOfflineRing(
        state = state,
        onClick = { if (state.complete || state.running || onTopUp == null) sheet.open(codexId) else onTopUp() },
        modifier = modifier,
    )
}
