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
    val current = feedback.firstOrNull()
    LaunchedEffect(current?.id) {
        if (current == null) return@LaunchedEffect
        if (current.succeeded && current.command is CodexCommand.Delete) onDeleted(current.command.id)
        val action = snackbar.showSnackbar(current.message, current.actionLabel, duration = SnackbarDuration.Long)
        owner.acknowledge(current.id, performAction = action == SnackbarResult.ActionPerformed)
    }
}
