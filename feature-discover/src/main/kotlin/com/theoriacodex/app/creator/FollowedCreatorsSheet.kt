package com.theoriacodex.app.creator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.app.ui.components.newPostsLabel
import com.theoriacodex.app.ui.undo.LocalUndoSlot
import com.theoriacodex.app.ui.undo.UndoRow
import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.data.repository.followKey
import com.theoriacodex.domain.model.CreatorProfile
import kotlinx.coroutines.delay

/**
 * Followed creators with their new-post counts. Opening it checks for new posts when the last check is stale;
 * unfollowing acts at once with Undo in the vacated row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowedCreatorsSheet(owner: CreatorFollowsViewModel, onDismiss: () -> Unit, onOpen: (CreatorProfile) -> Unit) {
    val follows by owner.follows.collectAsStateWithLifecycle()
    val unfollowed by owner.unfollowed.collectAsStateWithLifecycle()
    val refreshing by owner.refreshing.collectAsStateWithLifecycle()
    val errors by owner.errors.collectAsStateWithLifecycle()
    val undo = LocalUndoSlot.current
    val offer by undo.offer.collectAsStateWithLifecycle()
    // Only an unfollow made here holds its row; one made on a creator page draws its Undo there.
    val vacated = unfollowed?.takeIf { offer?.anchor == CreatorFollowAnchors.sheetRow(it.follow.creator) }
    val rows = remember(follows, vacated) { followedCreatorRows(follows, vacated) }
    LaunchedEffect(owner) { owner.refreshIfStale(System.currentTimeMillis()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetHeader("Followed creators", "Close followed creators", onDismiss) {
                if (follows.isNotEmpty()) CheckButton(refreshing, owner::refresh)
            }
            if (rows.isEmpty()) {
                EmptyState("No followed creators", Modifier.padding(vertical = 20.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rows, key = FollowedCreatorRow::key) { row ->
                        when (row) {
                            is FollowedCreatorRow.Followed -> FollowedCreatorCard(
                                row.follow, errors[row.follow.creator.followKey()], onOpen,
                            ) { owner.unfollow(it, undo) }
                            is FollowedCreatorRow.Vacated -> UndoRow(CreatorFollowAnchors.sheetRow(row.follow.creator))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Checks every followed creator for new posts; spins while a check runs, for at least [CHECK_MIN_SPIN_MS] so a
 * check that finds nothing new at once is still seen to run. New posts then show on their creators' rows.
 */
@Composable
internal fun CheckButton(refreshing: Boolean, onCheck: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    SideEffect { if (refreshing) holding = true }
    LaunchedEffect(holding) { if (holding) { delay(CHECK_MIN_SPIN_MS); holding = false } }
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (refreshing || holding) {
            CircularProgressIndicator(
                Modifier.size(24.dp).semantics {
                    contentDescription = "Checking for new posts"
                    liveRegion = LiveRegionMode.Polite
                },
                strokeWidth = 2.dp,
            )
        } else IconButton(onClick = onCheck) { Icon(Icons.Default.Refresh, contentDescription = "Check for new posts") }
    }
}

private const val CHECK_MIN_SPIN_MS = 800L

@Composable
private fun FollowedCreatorCard(
    follow: FollowedCreator,
    error: String?,
    onOpen: (CreatorProfile) -> Unit,
    onUnfollow: (CreatorProfile) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(follow.creator) },
        shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(58.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(follow.creator.displayName, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(follow.creator.source.displayName(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    newPostsLabel(follow.newPostCount)?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            FollowStateButton(followed = true) { onUnfollow(follow.creator) }
        }
    }
}
