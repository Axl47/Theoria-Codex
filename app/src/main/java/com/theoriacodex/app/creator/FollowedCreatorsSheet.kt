package com.theoriacodex.app.creator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.app.source.displayName
import com.theoriacodex.domain.model.CreatorProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FollowedCreatorsSheet(owner: CreatorFollowsViewModel, onDismiss: () -> Unit, onOpen: (CreatorProfile) -> Unit) {
    val follows by owner.follows.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetHeader("Followed authors", "Close authors menu", onDismiss)
            Text("Open an author’s page or unfollow them here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (follows.isEmpty()) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
                    Text("Open a creator page and tap the person-plus icon to save them here.",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                        textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(follows, key = { it.membershipId }) { follow ->
                        FollowedCreatorRow(follow, onOpen, owner::unfollow)
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowedCreatorRow(
    follow: com.theoriacodex.data.repository.FollowedCreator,
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
                Text(follow.creator.source.displayName(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CreatorFollowButton(followed = true, onClick = { onUnfollow(follow.creator) })
        }
    }
}
