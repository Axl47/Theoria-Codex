package com.theoriacodex.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.theoriacodex.app.codex.LocalQuickSave
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.lift.LiftOverlay
import com.theoriacodex.app.lift.LiftPhase
import com.theoriacodex.app.lift.LiftSession
import com.theoriacodex.app.ui.components.NameEntryDialog
import com.theoriacodex.app.ui.navigation.LocalLiftController
import com.theoriacodex.app.ui.navigation.LocalNewCodexOpener
import com.theoriacodex.app.ui.navigation.NewCodexOpener
import com.theoriacodex.app.ui.undo.LocalUndoSlot
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.launch

/** Holds the post waiting for a new Codex's name, and opens the dialog that asks for it; see [ShellLiftOverlay]. */
@Stable
internal class NewCodexRequests : NewCodexOpener {
    var post by mutableStateOf<Post?>(null)
        private set

    override fun openNewCodex(post: Post) {
        this.post = post
    }

    fun dismiss() {
        post = null
    }
}

/**
 * The lift overlay and the Create Codex dialog, mounted once above the navigation host. The lift draws the
 * [LiftSession] that [ShellCompositionLocals] provides and is cancelled on Back or when the app leaves the
 * foreground; Back also stops a Similar lookup it started. A save it makes offers Undo in the app's undo pill.
 * The dialog names a Codex and saves the post that the lift's New target, or the Viewer dock, passed to
 * [LocalNewCodexOpener].
 */
@Composable
internal fun ShellLiftOverlay(shell: TheoriaShell) {
    val newCodex = LocalNewCodexOpener.current
    val undo = LocalUndoSlot.current
    (LocalLiftController.current as? LiftSession)?.let { session ->
        BackHandler(enabled = session.phase is LiftPhase.Lifted || session.pending != null, onBack = session::cancel)
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { session.cancel() }
        LiftOverlay(
            session,
            onNewCodex = newCodex::openNewCodex,
            onFailure = shell::toast,
            onSaved = { record -> undo.awaitAction("Saved to ${record.codexName}", UndoAnchor.App) },
        )
    }
    (newCodex as? NewCodexRequests)?.let { requests ->
        requests.post?.let { post -> CreateCodexForPostDialog(shell, post, requests::dismiss) }
    }
}

/** Create Codex for the active profile, then quick-save [post] into it so each surface confirms as for any quick save. */
@Composable
private fun CreateCodexForPostDialog(shell: TheoriaShell, post: Post, onDone: () -> Unit) {
    val quickSave = LocalQuickSave.current
    NameEntryDialog(
        title = "Create Codex",
        initialName = "",
        onDismiss = onDone,
        onSave = { name ->
            val codexId = profileScopedCodexId(shell.owners.settings.state.value.activeProfile.profileId)
            shell.scope.launch {
                val created = runCatchingPreservingCancellation { shell.data.codexRepository.ensureCodex(codexId, name) }
                if (created.isFailure || quickSave.quickSave(post, codexId) == null) shell.toast("Could not save to $name")
            }
            onDone()
        },
    )
}
