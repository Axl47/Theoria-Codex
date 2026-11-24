package com.theoriacodex.app.codex

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.theoriacodex.app.media.CodexOfflineControls
import com.theoriacodex.app.media.CodexOfflineRing
import com.theoriacodex.app.media.CodexOfflineState
import com.theoriacodex.app.media.LocalCodexOfflineControls
import com.theoriacodex.app.media.LocalCodexOfflineStates
import com.theoriacodex.app.ui.components.ActionMenuRow

/**
 * Codex [codexId]'s offline ring on its detail screen, drawn only while the Codex is kept offline. A tap toggles
 * the copy: a full ring removes it (Undo on this screen), a running one opens the Library's offline sheet on its
 * row, and an incomplete or failed one tops it up or retries through [onTopUp], which skips what is already
 * kept. Without [onTopUp] those open the sheet instead.
 */
@Composable
internal fun CodexOfflineRingFor(codexId: String, onTopUp: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state = LocalCodexOfflineStates.current?.get(codexId) ?: return
    val tap = codexOfflineRingTap(codexId, state, LocalCodexOfflineControls.current, onTopUp)
    CodexOfflineRing(state, modifier, clickLabel = tap.label, onClick = tap.run)
}

/** Codex [codexId]'s offline ring on a Library tile: status only, so a tap anywhere on the tile opens the Codex. */
@Composable
internal fun CodexOfflineStatusFor(codexId: String, modifier: Modifier = Modifier) {
    LocalCodexOfflineStates.current?.get(codexId)?.let { CodexOfflineRing(it, modifier) }
}

/**
 * The Codex menu's offline row: "Make available offline" through [makeOffline] when the Codex is not kept offline,
 * "Remove offline copy" (deferred, with Undo where the menu was opened: Codex detail or the Library) when it is.
 * [close] dismisses the menu first.
 */
@Composable
internal fun CodexOfflineMenuRow(codexId: String, close: () -> Unit, makeOffline: (String) -> Unit) {
    val states = LocalCodexOfflineStates.current ?: return
    if (codexId in states) {
        val controls = LocalCodexOfflineControls.current
        ActionMenuRow(Icons.Default.CloudOff, "Remove offline copy") { close(); controls.remove(codexId) }
    } else {
        ActionMenuRow(Icons.Default.OfflinePin, "Make available offline") { close(); makeOffline(codexId) }
    }
}

/** What tapping the detail ring does, and the label TalkBack reads for it. */
internal class CodexOfflineRingTap(val label: String, val run: () -> Unit)

internal fun codexOfflineRingTap(
    codexId: String,
    state: CodexOfflineState,
    controls: CodexOfflineControls,
    onTopUp: (() -> Unit)?,
): CodexOfflineRingTap = when {
    state.complete -> CodexOfflineRingTap("Remove offline copy") { controls.remove(codexId) }
    state.running || onTopUp == null -> CodexOfflineRingTap("Show progress") { controls.openSheet(codexId) }
    state.failed -> CodexOfflineRingTap("Retry", onTopUp)
    else -> CodexOfflineRingTap("Download the rest", onTopUp)
}
