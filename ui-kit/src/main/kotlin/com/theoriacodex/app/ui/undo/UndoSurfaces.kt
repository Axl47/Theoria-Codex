package com.theoriacodex.app.ui.undo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import com.theoriacodex.app.ui.findActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Duration.Companion.milliseconds

val LocalUndoSlot = staticCompositionLocalOf { UndoSlot() }

@Composable
fun ProvideUndoSlot(slot: UndoSlot, content: @Composable () -> Unit) {
    val accessibility = LocalAccessibilityManager.current
    SideEffect {
        UndoTimeouts.stretch = { duration ->
            accessibility?.calculateRecommendedTimeoutMillis(
                duration.inWholeMilliseconds, containsIcons = false, containsText = true, containsControls = true,
            )?.milliseconds ?: duration
        }
    }
    CompositionLocalProvider(LocalUndoSlot provides slot, content = content)
}

@Composable
fun rememberUndoOffer(anchor: UndoAnchor): UndoOffer? {
    val offer by LocalUndoSlot.current.offer.collectAsStateWithLifecycle()
    return offer.shownAt(anchor)
}

@Composable
fun UndoLeaveEffect(anchorScope: String) {
    val slot = LocalUndoSlot.current
    SurfaceLeaveEffect(slot, anchorScope) { slot.expire(anchorScope) }
}

@Composable
fun SurfaceLeaveEffect(vararg keys: Any?, onLeave: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val leave by rememberUpdatedState(onLeave)
    DisposableEffect(*keys) {
        onDispose { if (activity?.isChangingConfigurations != true) leave() }
    }
}

@Composable
fun UndoPill(anchor: UndoAnchor, modifier: Modifier = Modifier) {
    UndoOfferVisibility(anchor, modifier) { offer, act -> UndoPillSurface(offer.label, offer.action.label, act) }
}

@Composable
private fun UndoPillSurface(label: String, actionLabel: String?, act: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        UndoOfferContent(label, actionLabel, act, MaterialTheme.colorScheme.inversePrimary, fill = false, Modifier.padding(start = 20.dp, end = 8.dp))
    }
}

@Composable
fun UndoRow(anchor: UndoAnchor, modifier: Modifier = Modifier) {
    UndoOfferVisibility(anchor, modifier.fillMaxWidth()) { offer, act -> UndoRowSurface(offer.label, offer.action.label, act) }
}

@Composable
fun DeferredRemovalRow(removal: PendingRemoval, onAct: () -> Unit, modifier: Modifier = Modifier) {
    UndoRowSurface(removal.shownLabel, removal.actionLabel, onAct, modifier)
}

@Composable
fun DeferredRemovalPill(removal: PendingRemoval, onAct: () -> Unit, modifier: Modifier = Modifier) {
    UndoPillSurface(removal.shownLabel, removal.actionLabel, onAct, modifier)
}

private val PendingRemoval.shownLabel: String
    get() = if (status == PendingRemoval.Status.Failed) REMOVAL_FAILED_LABEL else label

private val PendingRemoval.actionLabel: String?
    get() = when (status) {
        PendingRemoval.Status.Waiting -> UndoAction.Undo.label
        PendingRemoval.Status.Committing -> null
        PendingRemoval.Status.Failed -> UndoAction.Retry.label
    }

@Composable
private fun UndoRowSurface(label: String, actionLabel: String?, act: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        UndoOfferContent(label, actionLabel, act, MaterialTheme.colorScheme.primary, fill = true, Modifier.padding(start = 16.dp, end = 4.dp))
    }
}

private const val REMOVAL_FAILED_LABEL = "Could not remove"

@Composable
private fun UndoOfferVisibility(
    anchor: UndoAnchor,
    modifier: Modifier,
    content: @Composable (offer: UndoOffer, act: () -> Unit) -> Unit,
) {
    val slot = LocalUndoSlot.current
    val offer = rememberUndoOffer(anchor)
    val shown = remember { LastOffer() }
    if (offer != null) shown.value = offer
    AnimatedVisibility(
        visible = offer != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        shown.value?.let { last -> content(last) { slot.act(last) } }
    }
}

private class LastOffer(var value: UndoOffer? = null)

@Composable
private fun UndoOfferContent(label: String, actionLabel: String?, act: () -> Unit, actionColor: Color, fill: Boolean, modifier: Modifier) {
    Row(
        modifier = modifier.heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = fill),
        )
        if (actionLabel != null) {
            TextButton(onClick = act, colors = ButtonDefaults.textButtonColors(contentColor = actionColor)) {
                Text(actionLabel)
            }
        }
    }
}
