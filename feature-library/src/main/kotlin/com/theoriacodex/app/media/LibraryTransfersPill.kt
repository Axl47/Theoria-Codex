package com.theoriacodex.app.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * What the Library header shows about offline and download work: whether any job runs, how many posts
 * are still to save, and whether the sheet has anything at all.
 */
data class LibraryTransfersStatus(val active: Boolean, val remaining: Int, val hasContent: Boolean)

/** Sums running offline jobs (posts not yet settled) and active downloads. */
fun libraryTransfersStatus(offline: OfflineStorageUiState, batches: List<PostDownloadBatch>): LibraryTransfersStatus {
    val runningJobs = offline.owners.mapNotNull { owner -> owner.job?.takeIf { it.isRunning } }
    val offlineLeft = runningJobs.sumOf { (it.totalPosts - it.completedPosts - it.failedPosts.size).coerceAtLeast(0) }
    val downloadsLeft = batches.sumOf { batch -> batch.items.count { it.active } }
    return LibraryTransfersStatus(
        active = runningJobs.isNotEmpty() || downloadsLeft > 0 || offline.owners.any { it.preparing },
        remaining = offlineLeft + downloadsLeft,
        hasContent = offline.owners.isNotEmpty() || batches.any { it.items.isNotEmpty() },
    )
}

/**
 * The Library header's way into the offline and downloads sheet: a progress pill while work runs, a
 * plain button once something is kept or downloaded, and nothing before that.
 */
@Composable
fun LibraryTransfersButton(status: LibraryTransfersStatus, onClick: () -> Unit) {
    when {
        status.active -> Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.secondaryContainer,
            // Read as one button: the label with what is left, instead of the pill's own "3 left" text.
            modifier = Modifier.heightIn(min = 36.dp).clearAndSetSemantics {
                contentDescription = transfersDescription(status.remaining)
                role = Role.Button
                onClick { onClick(); true }
            },
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                if (status.remaining > 0) Text("${status.remaining} left", style = MaterialTheme.typography.labelLarge)
            }
        }
        status.hasContent -> IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = TRANSFERS_LABEL }) {
            Icon(Icons.Default.Download, contentDescription = null)
        }
    }
}

private fun transfersDescription(remaining: Int) =
    if (remaining > 0) "$TRANSFERS_LABEL, $remaining left" else "$TRANSFERS_LABEL, saving"

private const val TRANSFERS_LABEL = "Offline and downloads"
