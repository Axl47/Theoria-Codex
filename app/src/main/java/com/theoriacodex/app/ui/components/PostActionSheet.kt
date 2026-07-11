package com.theoriacodex.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.creator.CreatorProfileActionButton
import com.theoriacodex.app.media.copyPostTagsToClipboard
import com.theoriacodex.app.media.copyPostUrlToClipboard
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey

/**
 * The product's concrete post-action surface.
 *
 * Download, collection, clipboard, sharing, creator navigation, and dismissal keep one interaction
 * contract while callers retain ownership of route-specific tag content and callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostActionSheet(
    post: Post,
    creatorBrowsingSources: Set<SourceKey> = emptySet(),
    onDismiss: () -> Unit,
    onSaveToDevice: () -> Unit,
    onSaveToCodex: (() -> Unit)? = null,
    onRemoveFromCodex: (() -> Unit)? = null,
    onOpenCreatorProfile: ((CreatorProfile) -> Unit)? = null,
    onOpenLegacyCreatorProfile: (() -> Unit)? = null,
    onGoToSearch: (() -> Unit)? = null,
    tagContent: @Composable () -> Unit,
) {
    require(onSaveToCodex == null || onRemoveFromCodex == null) {
        "A post action sheet cannot save to and remove from a Codex at the same time"
    }
    require((onOpenCreatorProfile == null) == (onOpenLegacyCreatorProfile == null)) {
        "Creator profile callbacks must be supplied together"
    }

    val context = LocalContext.current
    fun dismissThen(action: () -> Unit) {
        onDismiss()
        action()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { dismissThen(onSaveToDevice) }) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Save to device",
                    )
                }
                onSaveToCodex?.let { save ->
                    IconButton(onClick = { dismissThen(save) }) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdd,
                            contentDescription = "Save to Codex",
                        )
                    }
                }
                onRemoveFromCodex?.let { remove ->
                    IconButton(onClick = { dismissThen(remove) }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove from Codex",
                        )
                    }
                }
                IconButton(
                    onClick = {
                        copyPostTagsToClipboard(context, post)
                        Toast.makeText(context, "Tags copied", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy tags",
                    )
                }
                IconButton(
                    onClick = {
                        val copied = copyPostUrlToClipboard(context, post)
                        Toast.makeText(
                            context,
                            if (copied) "Post URL copied" else "No post URL available",
                            Toast.LENGTH_SHORT,
                        ).show()
                        onDismiss()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                    )
                }
            }
            Text(
                text = post.title?.takeIf(String::isNotBlank) ?: post.id.sourcePostId,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            if (onOpenCreatorProfile != null && onOpenLegacyCreatorProfile != null) {
                CreatorProfileActionButton(
                    post = post,
                    creatorBrowsingSources = creatorBrowsingSources,
                    onOpenProfile = { profile -> dismissThen { onOpenCreatorProfile(profile) } },
                    onOpenLegacyPost = { dismissThen(onOpenLegacyCreatorProfile) },
                )
            }
            HorizontalDivider()
            tagContent()
            onGoToSearch?.let { goToSearch ->
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { dismissThen(goToSearch) },
                ) {
                    Text("Go to Search")
                }
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            ) {
                Text("Cancel")
            }
        }
    }
}
