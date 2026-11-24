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

@Composable
internal fun CodexOfflineRingFor(codexId: String, onTopUp: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state = LocalCodexOfflineStates.current?.get(codexId) ?: return
    val tap = codexOfflineRingTap(codexId, state, LocalCodexOfflineControls.current, onTopUp)
    CodexOfflineRing(state, modifier, clickLabel = tap.label, onClick = tap.run)
}

@Composable
internal fun CodexOfflineStatusFor(codexId: String, modifier: Modifier = Modifier) {
    LocalCodexOfflineStates.current?.get(codexId)?.let { CodexOfflineRing(it, modifier) }
}

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
