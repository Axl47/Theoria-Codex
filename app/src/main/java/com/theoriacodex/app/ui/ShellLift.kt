package com.theoriacodex.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.theoriacodex.app.codex.CreateCodexDialog
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.lift.LiftOverlay
import com.theoriacodex.app.lift.LiftPhase
import com.theoriacodex.app.lift.LiftSession
import com.theoriacodex.app.ui.navigation.LocalLiftController
import com.theoriacodex.domain.model.Post

/**
 * The lift overlay, mounted once above the navigation host. Draws the [LiftSession] that [ShellCompositionLocals]
 * provides, cancels a lift on Back or when the app leaves the foreground, and names and creates a Codex for a
 * post dropped on New.
 */
@Composable
internal fun ShellLiftOverlay(shell: TheoriaShell) {
    val session = LocalLiftController.current as? LiftSession ?: return
    var newCodexPost by remember { mutableStateOf<Post?>(null) }
    BackHandler(enabled = session.phase is LiftPhase.Lifted, onBack = session::cancel)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { session.cancel() }
    LiftOverlay(session, onNewCodex = { newCodexPost = it }, onFailure = shell::toast)
    newCodexPost?.let { post -> CreateCodexForPostDialog(shell, post) { newCodexPost = null } }
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
