package com.theoriacodex.app.codex

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.theoriacodex.domain.model.Codex
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Which icon marks each Saved collection; anything else is the profile's Likes. */
internal fun savedCodexChildIcon(codexId: String): ImageVector = when (codexId) {
    FOLLOWED_CODEX_ID -> Icons.Default.Person
    SAVED_SEARCHES_CODEX_ID -> Icons.Default.Search
    else -> Icons.Default.Favorite
}

internal fun savedCodexCountSuffix(codexId: String): String? = when (codexId) {
    FOLLOWED_CODEX_ID -> "creators"
    SAVED_SEARCHES_CODEX_ID -> "saved"
    else -> null
}

/** What fills an empty collection, shown in place of a blank tile. */
private fun savedCodexEmptyHint(codexId: String): String = when (codexId) {
    FOLLOWED_CODEX_ID -> "Creators you follow"
    SAVED_SEARCHES_CODEX_ID -> "Searches you save"
    else -> "Posts you like"
}

/** The library's Saved entry: each collection has its own cover and opens directly, Likes largest. */
@Composable
internal fun SavedCodexLibraryTile(
    presentation: CodexListPresentation,
    onOpen: (String) -> Unit,
    onOpenActions: (String) -> Unit,
) {
    val (likes, followed, searches) = savedCodexChildren(presentation.likesCodexId)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(SAVED_CODEX_ID) }
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Saved", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("Open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Open Saved", tint = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.fillMaxWidth().height(168.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SavedCollectionTile(likes, presentation, onOpen, onOpenActions, Modifier.weight(1.25f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SavedCollectionTile(followed, presentation, onOpen, onOpenActions, Modifier.weight(1f).fillMaxWidth())
                SavedCollectionTile(searches, presentation, onOpen, onOpenActions, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SavedCollectionTile(
    collection: Codex,
    presentation: CodexListPresentation,
    onOpen: (String) -> Unit,
    onOpenActions: (String) -> Unit,
    modifier: Modifier,
) {
    val count = presentation.itemCounts[collection.codexId] ?: 0
    val covers = presentation.coverCandidates[collection.codexId].orEmpty()
    val onCover = covers.isNotEmpty()
    val textColor = if (onCover) Color.White else MaterialTheme.colorScheme.onSurface
    Box(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(
                onClick = { onOpen(collection.codexId) },
                onLongClick = { onOpenActions(collection.codexId) },
                onLongClickLabel = "Actions for ${collection.name}",
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
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(savedCodexChildIcon(collection.codexId), null, Modifier.size(16.dp), tint = textColor)
                Text(
                    collection.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = textColor)
            }
            if (count == 0 && !onCover) {
                Text(
                    savedCodexEmptyHint(collection.codexId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
