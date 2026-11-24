package com.theoriacodex.app.creator

import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import com.theoriacodex.app.ui.undo.LocalUndoSlot
import com.theoriacodex.app.ui.undo.rememberUndoOffer
import com.theoriacodex.domain.model.CreatorProfile

internal val CreatorProfile.canFollow: Boolean get() = !uploadsQuery.isNullOrBlank()

@Composable
internal fun CreatorFollowButton(creator: CreatorProfile, followed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (!creator.canFollow) return
    val slot = LocalUndoSlot.current
    val offer = rememberUndoOffer(CreatorFollowAnchors.page(creator))
    if (offer != null) {
        TextButton(
            onClick = { slot.act(offer) },
            modifier = Modifier.clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                role = Role.Button
                contentDescription = offer.label
                onClick(label = offer.action.label) { slot.act(offer); true }
            },
        ) { Text(offer.action.label) }
    } else {
        FollowStateButton(followed, enabled, onClick)
    }
}

@Composable
internal fun FollowStateButton(followed: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    if (followed) OutlinedButton(onClick = onClick, enabled = enabled) { Text("Following") }
    else FilledTonalButton(onClick = onClick, enabled = enabled) { Text("Follow") }
}
