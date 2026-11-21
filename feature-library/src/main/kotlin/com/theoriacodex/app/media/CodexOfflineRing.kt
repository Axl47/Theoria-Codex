package com.theoriacodex.app.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The offline copy of a Codex kept offline: [fraction] of its posts retained (0 to 1), whether a job is
 * [running], and whether the last one [failed]. Codices not kept offline have no state and show no ring.
 */
data class CodexOfflineState(val fraction: Float, val running: Boolean = false, val failed: Boolean = false) {
    val complete: Boolean get() = fraction >= 1f && !running && !failed
}

/** Offline state by Codex id, for Codices kept offline only. Empty until the Library provides it, so no ring shows. */
val LocalCodexOfflineStates = compositionLocalOf<Map<String, CodexOfflineState>> { emptyMap() }

/**
 * What can be done to a Codex's offline copy from outside the Library's offline sheet. The app provides it over
 * every screen through [LocalCodexOfflineControls]; the default does nothing, for previews and tests.
 */
interface CodexOfflineControls {
    /** Opens the Library's offline sheet, scrolled to and marking [codexId]'s row when one is given. */
    fun openSheet(codexId: String?)

    /**
     * Removes [codexId]'s offline copy as deferred destruction until the surface showing its Undo is left: the ring
     * hides at once and [LocalCodexOfflineRemovals] holds the removal for [CodexOfflineRemovalUndo].
     */
    fun remove(codexId: String)

    /** Undo, or Retry after a failure, for [codexId]'s pending removal. */
    fun actOnRemoval(codexId: String)

    /** Commits every pending offline removal; the surface showing their Undo calls it when left. */
    fun commitRemovals()

    companion object {
        val None = object : CodexOfflineControls {
            override fun openSheet(codexId: String?) = Unit
            override fun remove(codexId: String) = Unit
            override fun actOnRemoval(codexId: String) = Unit
            override fun commitRemovals() = Unit
        }
    }
}

val LocalCodexOfflineControls = staticCompositionLocalOf { CodexOfflineControls.None }

/**
 * A small progress ring for a Codex kept offline: full, running and failed each look different. With [onClick]
 * it is a button whose [clickLabel] says what a tap does; without, it is status only.
 */
@Composable
fun CodexOfflineRing(
    state: CodexOfflineState,
    modifier: Modifier = Modifier,
    clickLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val description = codexOfflineDescription(state)
    val tappable = if (onClick == null) Modifier else {
        Modifier.minimumInteractiveComponentSize().clip(CircleShape)
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
    }
    Box(modifier.then(tappable).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (state.complete) {
            Icon(Icons.Default.OfflinePin, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        } else {
            val (color, badge) = when {
                state.failed -> MaterialTheme.colorScheme.error to Icons.Default.PriorityHigh
                state.running -> MaterialTheme.colorScheme.primary to Icons.Default.ArrowDownward
                else -> MaterialTheme.colorScheme.onSurfaceVariant to null
            }
            PartialRing(state.fraction, color, badge)
        }
    }
}

@Composable
private fun PartialRing(fraction: Float, color: Color, badge: ImageVector?) {
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.size(22.dp),
            color = color,
            strokeWidth = 2.5.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        badge?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(12.dp)) }
    }
}

/** What TalkBack reads for the ring. */
internal fun codexOfflineDescription(state: CodexOfflineState): String {
    val percent = (state.fraction.coerceIn(0f, 1f) * 100).roundToInt()
    return when {
        state.complete -> "Available offline"
        // No percent: failed posts count as settled, so a failed ring is often full.
        state.failed -> "Offline copy failed"
        state.running -> "Making available offline, $percent%"
        else -> "Offline copy $percent%"
    }
}
