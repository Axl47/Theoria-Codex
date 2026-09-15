package com.theoriacodex.app.creator

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.source.displayName
import com.theoriacodex.data.repository.followKey
import com.theoriacodex.domain.model.CreatorProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FollowedCreatorsSheet(owner: CreatorFollowsViewModel, onDismiss: () -> Unit, onOpen: (CreatorProfile) -> Unit) {
    val follows by owner.follows.collectAsStateWithLifecycle()
    val refreshing by owner.refreshing.collectAsStateWithLifecycle()
    val errors by owner.errors.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp)) {
            item {
                Text("Followed authors", style = MaterialTheme.typography.titleLarge)
                Text("Saved on this device. Check for new posts in each creator’s latest page.")
                TextButton(enabled = follows.isNotEmpty(), onClick = {
                    if (refreshing) owner.cancelRefresh() else owner.refresh()
                }) { Text(if (refreshing) "Cancel check" else "Check for new posts") }
            }
            if (follows.isEmpty()) item { Text("Open a creator page and tap Follow to save them here.") }
            items(follows, key = { it.membershipId }) { follow ->
                FollowedCreatorRow(follow, errors[follow.creator.followKey()], onOpen, owner::unfollow)
            }
        }
    }
}

@Composable
private fun FollowedCreatorRow(
    follow: com.theoriacodex.data.repository.FollowedCreator,
    error: String?,
    onOpen: (CreatorProfile) -> Unit,
    onUnfollow: (CreatorProfile) -> Unit,
) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f).clickable { onOpen(follow.creator) }.padding(vertical = 8.dp)) {
                        Text(follow.creator.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(follow.creator.source.displayName())
                        Text(error ?: when {
                            follow.newPostCount > 0 -> "${follow.newPostCount} new in latest page"
                            follow.checkedAtEpochMs == null -> "Not checked yet"
                            else -> "No new posts in latest check"
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onUnfollow(follow.creator) }) { Text("Unfollow") }
                }
}
