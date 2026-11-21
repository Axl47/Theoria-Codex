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
 * given; plain notices show in [snackbar], newest only. [onDeleted] hears the ids of Codices waiting to be deleted
 * whenever they change (so the screen of one can close); it must be idempotent.
 */
@Composable
fun CodexCommandHost(owner: CodexCommandViewModel, snackbar: SnackbarHostState, onDeleted: (String) -> Unit) {
    val undo = LocalUndoSlot.current
    val feedback by owner.feedback.collectAsStateWithLifecycle()
    val deleting by owner.pendingDeletes.collectAsStateWithLifecycle()
    val notice = feedback.lastOrNull { it.action == null && !it.quiet }
    LaunchedEffect(deleting.keys) { deleting.keys.forEach(onDeleted) }
    LaunchedEffect(feedback) {
        // The notice still showing keeps its own delivery below.
        feedback.filter { it.id != notice?.id }.forEach { item ->
            if (item.action != null) owner.offer(item.id, undo) else owner.acknowledge(item.id)
        }
    }
    LaunchedEffect(notice?.id) {
        if (notice == null) return@LaunchedEffect
        snackbar.showSnackbar(notice.message, duration = SnackbarDuration.Short)
        owner.acknowledge(notice.id)
    }
}
