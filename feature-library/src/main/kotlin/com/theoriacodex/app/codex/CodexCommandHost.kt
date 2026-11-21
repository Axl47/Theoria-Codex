package com.theoriacodex.app.codex

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.undo.LocalUndoSlot

/**
 * Delivers [owner]'s feedback: Undo and Retry go to the app's undo slot, anchored where each command was
 * given; plain notices show in [snackbar], newest only. [onDeleted] hears each successful Codex deletion once.
 */
@Composable
fun CodexCommandHost(owner: CodexCommandViewModel, snackbar: SnackbarHostState, onDeleted: (String) -> Unit) {
    val undo = LocalUndoSlot.current
    val feedback by owner.feedback.collectAsStateWithLifecycle()
    val notice = feedback.lastOrNull { it.action == null }
    LaunchedEffect(feedback) {
        // Deliver navigation before handing off; the notice still showing keeps its own delivery below.
        feedback.filter { it.id != notice?.id }.forEach { item ->
            if (item.succeeded && item.command is CodexCommand.Delete) onDeleted(item.command.id)
            if (item.action != null) owner.offer(item.id, undo) else owner.acknowledge(item.id, performAction = false)
        }
    }
    LaunchedEffect(notice?.id) {
        if (notice == null) return@LaunchedEffect
        if (notice.succeeded && notice.command is CodexCommand.Delete) onDeleted(notice.command.id)
        snackbar.showSnackbar(notice.message, duration = SnackbarDuration.Short)
        owner.acknowledge(notice.id, performAction = false)
    }
}
