package com.theoriacodex.app.codex

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun CodexCommandHost(owner: CodexCommandViewModel, snackbar: SnackbarHostState, onDeleted: (String) -> Unit) {
    val feedback by owner.feedback.collectAsStateWithLifecycle()
    val current = feedback.firstOrNull { it.action != null } ?: feedback.lastOrNull()
    LaunchedEffect(feedback) {
        // A newer action must not wait behind success messages. Deliver navigation before
        // acknowledging superseded notices; actionable feedback remains queued until handled.
        feedback.filter { it.id != current?.id && it.action == null }.forEach { notice ->
            if (notice.succeeded && notice.command is CodexCommand.Delete) onDeleted(notice.command.id)
            owner.acknowledge(notice.id, performAction = false)
        }
    }
    LaunchedEffect(current?.id) {
        if (current == null) return@LaunchedEffect
        if (current.succeeded && current.command is CodexCommand.Delete) onDeleted(current.command.id)
        val duration = if (current.action == null) SnackbarDuration.Short else SnackbarDuration.Long
        val action = snackbar.showSnackbar(current.message, current.actionLabel, duration = duration)
        owner.acknowledge(current.id, performAction = action == SnackbarResult.ActionPerformed)
    }
}
