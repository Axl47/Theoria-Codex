package com.theoriacodex.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.theoriacodex.app.codex.ChosenCodex
import com.theoriacodex.app.codex.LocalNewCodexOpener
import com.theoriacodex.app.codex.NewCodexOpener
import com.theoriacodex.app.lift.LiftOverlay
import com.theoriacodex.app.lift.LiftPhase
import com.theoriacodex.app.lift.LiftSession
import com.theoriacodex.app.ui.components.NameEntryDialog
import com.theoriacodex.app.ui.navigation.LocalLiftController
import com.theoriacodex.app.ui.undo.LocalUndoSlot
import com.theoriacodex.app.ui.undo.UndoAnchor
import kotlinx.coroutines.launch

@Stable
internal class NewCodexRequests : NewCodexOpener {
    var onChosen by mutableStateOf<(suspend (ChosenCodex.New) -> Unit)?>(null)
        private set

    override fun openNewCodex(onChosen: suspend (ChosenCodex.New) -> Unit) {
        this.onChosen = onChosen
    }

    fun dismiss() {
        onChosen = null
    }
}

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
            onOpenPicker = { post, query, onChosen -> shell.pendingSave.request(listOf(post), searchQuery = query, onPick = onChosen) },
            onFailure = shell::toast,
            onSaved = { record -> undo.awaitAction("Saved to ${record.codexName}", UndoAnchor.App) },
        )
    }
    (newCodex as? NewCodexRequests)?.let { requests ->
        requests.onChosen?.let { onChosen ->
            NameEntryDialog(
                title = "Create Codex",
                initialName = "",
                onDismiss = requests::dismiss,
                onSave = { name ->
                    val codex = ChosenCodex.New(name, shell.owners.settings.state.value.activeProfile.profileId)
                    shell.scope.launch { onChosen(codex) }
                    requests.dismiss()
                },
            )
        }
    }
}
