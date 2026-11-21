package com.theoriacodex.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.theoriacodex.app.codex.CreateCodexDialog
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.lift.LiftOverlay
import com.theoriacodex.app.lift.LiftPhase
import com.theoriacodex.app.lift.LiftSession
import com.theoriacodex.app.ui.navigation.LocalLiftController
import com.theoriacodex.app.ui.navigation.LocalNewCodexOpener
import com.theoriacodex.app.ui.navigation.NewCodexOpener
import com.theoriacodex.domain.model.Post

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
 * foreground. The dialog names a Codex and saves the post that the lift's New target, or the Viewer dock, passed
 * to [LocalNewCodexOpener].
 */
@Composable
internal fun ShellLiftOverlay(shell: TheoriaShell) {
    val newCodex = LocalNewCodexOpener.current
    (LocalLiftController.current as? LiftSession)?.let { session ->
        BackHandler(enabled = session.phase is LiftPhase.Lifted, onBack = session::cancel)
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { session.cancel() }
        LiftOverlay(session, onNewCodex = newCodex::openNewCodex, onFailure = shell::toast)
    }
    (newCodex as? NewCodexRequests)?.let { requests ->
        requests.post?.let { post -> CreateCodexForPostDialog(shell, post, requests::dismiss) }
    }
}

/** Create Codex for the active profile, saving [post] into the new Codex. */
@Composable
private fun CreateCodexForPostDialog(shell: TheoriaShell, post: Post, onDone: () -> Unit) {
    val settings = shell.owners.settings
    CreateCodexDialog(
        profileName = settings.state.value.activeProfile.name,
        onDismiss = onDone,
        onCreate = { name ->
            val state = settings.state.value
            shell.owners.codexSaves.save(
                codexId = profileScopedCodexId(state.activeProfile.profileId),
                posts = listOf(post),
                cacheFullImage = state.settings.cache.cacheFullImageOnSave,
                fromForYou = false,
                newCollectionName = name,
            )
            onDone()
        },
    )
}
