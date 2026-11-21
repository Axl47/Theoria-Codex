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
 * Says for a couple of seconds, at the edge the card went to, that the deck's latest decision is done or that it
 * failed and its card is back. A skip says nothing: the card leaving is the confirmation.
 */
@Composable
internal fun BoxScope.TriageConfirmation(notice: TriageNotice?) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(notice) {
        visible = notice != null
        delay(CONFIRMATION_MS)
        visible = false
    }
    val label = notice?.takeIf { visible }?.let(::noticeLabel) ?: return
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier
            .align(noticeAlignment(notice.decision))
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

internal fun noticeLabel(notice: TriageNotice): String? = when (val decision = notice.decision) {
    is TriageDecision.Liked -> if (notice.failed) "Couldn't like" else "Liked"
    is TriageDecision.Saved -> if (notice.failed) "Couldn't save" else "Saved to ${decision.codexName}"
    is TriageDecision.Hid -> if (notice.failed) "Couldn't hide" else "Hid ${tagLabel(decision.tag)}"
    is TriageDecision.Removed -> if (notice.failed) "Couldn't remove" else "Removed"
    is TriageDecision.Kept -> "Kept"
    is TriageDecision.Skipped -> null
}

/** A tag as the Viewer's tag chips show it. */
internal fun tagLabel(tag: String): String = tag.replace('_', ' ')

private fun noticeAlignment(decision: TriageDecision): Alignment = when (decision) {
    is TriageDecision.Liked, is TriageDecision.Kept -> Alignment.CenterEnd
    is TriageDecision.Saved -> Alignment.TopCenter
    is TriageDecision.Hid -> Alignment.BottomCenter
    is TriageDecision.Skipped, is TriageDecision.Removed -> Alignment.CenterStart
}

internal const val TRIAGE_CONFIRMATION_TAG = "triage-confirmation"
private const val CONFIRMATION_MS = 2_500L
private const val CUE_MIN_SCALE = 0.8f
