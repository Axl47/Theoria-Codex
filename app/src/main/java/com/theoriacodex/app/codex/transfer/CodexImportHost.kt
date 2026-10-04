package com.theoriacodex.app.codex.transfer

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.routes.CollectRouteEffects

@Composable
internal fun CodexImportHost(owner: CodexImportViewModel, snackbar: SnackbarHostState) {
    val state by owner.state.collectAsStateWithLifecycle()
    CollectRouteEffects(owner.effects) { result ->
        val retryable = result == CodexImportResult.Failure || result == CodexImportResult.TimedOut || result == CodexImportResult.Unreadable
        val message = when (result) {
            CodexImportResult.Failure -> "Could not import Codex. Please try again."
            CodexImportResult.TimedOut -> "Import timed out before saving. Please try again."
            CodexImportResult.Unreadable -> "Could not read Codex file."
            CodexImportResult.Invalid -> "Unsupported or invalid Codex file. Files must be 32 MiB or smaller."
            is CodexImportResult.Success -> "Imported ${result.imported} posts" +
                if (result.skipped > 0) " (${result.skipped} skipped)" else ""
        }
        if (snackbar.showSnackbar(message, actionLabel = if (retryable) "Retry" else null) == SnackbarResult.ActionPerformed) owner.retry()
    }
    if (state.running) {
        val progress = state.progress
        AlertDialog(
            onDismissRequest = owner::cancel,
            title = { Text("Importing Codex") },
            text = { Text(when {
                progress == null -> "Reading document…"
                progress.committing -> "Saving ${progress.total} posts…"
                else -> "Prepared ${progress.completed} of ${progress.total} posts"
            }) },
            confirmButton = {
                TextButton(onClick = owner::cancel, enabled = progress?.committing != true) { Text("Cancel") }
            },
        )
    }
}
