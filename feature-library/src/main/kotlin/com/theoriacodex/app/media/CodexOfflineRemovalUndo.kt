package com.theoriacodex.app.media

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.theoriacodex.app.ui.undo.DeferredRemovalPill
import com.theoriacodex.app.ui.undo.PendingRemoval
import com.theoriacodex.app.ui.undo.SurfaceLeaveEffect

val LocalCodexOfflineRemovals = compositionLocalOf<Map<String, PendingRemoval>> { emptyMap() }

@Composable
fun CodexOfflineRemovalUndo(modifier: Modifier = Modifier, codexId: String? = null) {
    val controls = LocalCodexOfflineControls.current
    SurfaceLeaveEffect { controls.commitRemovals() }
    val shown = LocalCodexOfflineRemovals.current.filter { (id, removal) ->
        (codexId == null || id == codexId) && removal.status != PendingRemoval.Status.Committing
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        shown.forEach { (id, removal) ->
            key(id) {
                DeferredRemovalPill(removal.copy(label = REMOVED_OFFLINE_COPY), onAct = { controls.actOnRemoval(id) })
            }
        }
    }
}

private const val REMOVED_OFFLINE_COPY = "Removed offline copy"
