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

data class CodexOfflineState(val fraction: Float, val running: Boolean = false, val failed: Boolean = false) {
    val complete: Boolean get() = fraction >= 1f && !running && !failed
}

val LocalCodexOfflineStates = compositionLocalOf<Map<String, CodexOfflineState>?> { null }

interface CodexOfflineControls {
    fun openSheet(codexId: String?)

    fun remove(codexId: String)

    fun actOnRemoval(codexId: String)

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

internal fun codexOfflineDescription(state: CodexOfflineState): String {
    val percent = (state.fraction.coerceIn(0f, 1f) * 100).roundToInt()
    return when {
        state.complete -> "Available offline"
        state.failed -> "Offline copy failed"
        state.running -> "Making available offline, $percent%"
        else -> "Offline copy $percent%"
    }
}
