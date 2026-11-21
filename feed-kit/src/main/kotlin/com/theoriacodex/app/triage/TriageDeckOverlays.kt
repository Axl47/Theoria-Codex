package com.theoriacodex.app.triage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The circle that fades in at the edge a card is being pulled toward, for the directions without targets. */
@Composable
internal fun BoxScope.TriageEdgeCue(icon: ImageVector, alignment: Alignment, progress: Float) {
    if (progress <= 0f) return
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier
            .align(alignment)
            .padding(16.dp)
            .size(56.dp)
            .alpha(progress)
            .scale(CUE_MIN_SCALE + (1f - CUE_MIN_SCALE) * progress),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
    }
}

/**
 * Confirms the deck's last decision for a couple of seconds at the edge it went to. A skip says nothing: the
 * card leaving is the confirmation.
 */
@Composable
internal fun BoxScope.TriageConfirmation(decision: TriageDecision?) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(decision?.post?.id) {
        visible = decision != null
        delay(CONFIRMATION_MS)
        visible = false
    }
    val label = decision?.takeIf { visible }?.let(::confirmationLabel) ?: return
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier
            .align(confirmationAlignment(decision))
            .padding(16.dp)
            .testTag(TRIAGE_CONFIRMATION_TAG)
            .semantics { contentDescription = label },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

internal fun confirmationLabel(decision: TriageDecision): String? = when (decision) {
    is TriageDecision.Liked -> "Liked"
    is TriageDecision.Saved -> "Saved to ${decision.codexName}"
    is TriageDecision.Hid -> "Hid ${decision.tag}"
    is TriageDecision.Skipped -> null
}

private fun confirmationAlignment(decision: TriageDecision): Alignment = when (decision) {
    is TriageDecision.Liked -> Alignment.CenterEnd
    is TriageDecision.Saved -> Alignment.TopCenter
    is TriageDecision.Hid -> Alignment.BottomCenter
    is TriageDecision.Skipped -> Alignment.CenterStart
}

internal const val TRIAGE_CONFIRMATION_TAG = "triage-confirmation"
private const val CONFIRMATION_MS = 2_500L
private const val CUE_MIN_SCALE = 0.8f
