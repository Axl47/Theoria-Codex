package com.theoriacodex.app.creator

import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.theoriacodex.app.ui.undo.LocalUndoSlot
import com.theoriacodex.app.ui.undo.rememberUndoOffer
import com.theoriacodex.domain.model.CreatorProfile

/** Whether a creator can be followed: following checks its uploads feed, so a creator without one cannot. */
internal val CreatorProfile.canFollow: Boolean get() = !uploadsQuery.isNullOrBlank()

/**
 * The creator page's Follow / Following button, the same on every layout and absent when [creator] cannot be
 * followed. After an unfollow, Undo takes the button's place until the offer expires.
 */
@Composable
internal fun CreatorFollowButton(creator: CreatorProfile, followed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (!creator.canFollow) return
    val slot = LocalUndoSlot.current
    val offer = rememberUndoOffer(CreatorFollowAnchors.page(creator))
    if (offer != null) {
        TextButton(
            onClick = { slot.act(offer) },
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "${offer.label}. ${offer.action.label}"
            },
        ) { Text(offer.action.label) }
    } else {
        FollowStateButton(followed, enabled, onClick)
    }
}

/** Follow when [followed] is false, Following when true; a tap on either toggles it. */
@Composable
internal fun FollowStateButton(followed: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    if (followed) OutlinedButton(onClick = onClick, enabled = enabled) { Text("Following") }
    else FilledTonalButton(onClick = onClick, enabled = enabled) { Text("Follow") }
}
