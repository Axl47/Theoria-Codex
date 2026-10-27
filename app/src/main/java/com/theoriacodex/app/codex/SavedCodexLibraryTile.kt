package com.theoriacodex.app.codex

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
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The library's full-row entry gives each Saved collection its own readable count and name. */
@Composable
internal fun SavedCodexLibraryTile(presentation: CodexListPresentation, onOpen: (String) -> Unit) {
    val children = savedCodexChildren(presentation.likesCodexId)
    val covers = children.flatMap { presentation.coverCandidates[it.codexId].orEmpty() }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(132.dp).clickable { onOpen(SAVED_CODEX_ID) }
                .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                CodexCoverImage(covers, "Saved", Modifier.fillMaxSize()) {
                    Icon(Icons.Default.CollectionsBookmark, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().clickable { onOpen(SAVED_CODEX_ID) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Saved", style = MaterialTheme.typography.headlineMedium)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Open Saved")
            }
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                children.forEach { child ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onOpen(child.codexId) }.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${presentation.itemCounts[child.codexId] ?: 0}", style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text(child.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
