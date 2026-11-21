package com.theoriacodex.app.codex

import com.theoriacodex.app.ui.codexMorph
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Which icon marks each Saved collection; anything else is the profile's Likes. */
fun savedCodexChildIcon(codexId: String): ImageVector = when (codexId) {
    FOLLOWED_CODEX_ID -> Icons.Default.Person
    SAVED_SEARCHES_CODEX_ID -> Icons.Default.Search
    else -> Icons.Default.Favorite
}

/** The word after a Saved shelf's [count], if its collection is counted in something other than items. */
fun savedCodexCountSuffix(codexId: String, count: Int): String? = when (codexId) {
    FOLLOWED_CODEX_ID -> countNoun(count, "creator")
    SAVED_SEARCHES_CODEX_ID -> "saved"
    else -> null
}

/**
 * Every Codex in the library: name and count over its cover, with actions on long press, and the offline ring in
 * the corner while the Codex is kept offline ([onTopUpOffline] tops an incomplete copy up). Without
 * [longPressOpensActions] the grid owns the long-press gesture (Manual sort's drag) and only accessibility services
 * open the actions here.
 */
@Composable
internal fun CodexCoverTile(
    codexId: String,
    name: String,
    itemCount: Int,
    covers: List<CodexCoverCandidate>,
    onOpen: () -> Unit,
    onOpenActions: () -> Unit,
    modifier: Modifier,
    onTopUpOffline: (() -> Unit)? = null,
    longPressOpensActions: Boolean = true,
) {
    val onCover = covers.isNotEmpty()
    val actionsLabel = "Actions for $name"
    val textColor = if (onCover) Color.White else MaterialTheme.colorScheme.onSurface
    Box(
        modifier.codexMorph(codexId).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (longPressOpensActions) {
                    Modifier.combinedClickable(onClick = onOpen, onLongClick = onOpenActions, onLongClickLabel = actionsLabel)
                } else {
                    Modifier.clickable(onClick = onOpen).semantics { onLongClick(actionsLabel) { onOpenActions(); true } }
                },
            ),
    ) {
        if (onCover) {
            CodexCoverImage(covers, null, Modifier.fillMaxSize()) {}
            // Covers vary, so a scrim keeps the name and count readable over any of them.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f)),
                ),
            )
        }
        CodexOfflineRingFor(
            codexId, onTopUpOffline,
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape),
        )
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(itemCount.toString(), style = MaterialTheme.typography.labelLarge, color = textColor)
            }
        }
    }
}
