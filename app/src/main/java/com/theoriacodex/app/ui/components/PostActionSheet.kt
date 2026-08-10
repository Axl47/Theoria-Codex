package com.theoriacodex.app.ui.components

import android.content.Context
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
import com.theoriacodex.app.media.showClipboardCopyConfirmation
import com.theoriacodex.app.post.displayTitleOrNull
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
    onPostUrlCopied: (Post) -> Unit = {},
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
                        copyPostTagsWithFeedback(context, post)
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
                        copyPostUrlWithFeedback(context, post, onPostUrlCopied)
                        onDismiss()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                    )
                }
            }
            post.displayTitleOrNull()?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
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

private fun copyPostTagsWithFeedback(context: Context, post: Post) {
    if (copyPostTagsToClipboard(context, post)) {
        showClipboardCopyConfirmation(context, "Tags copied")
    } else {
        Toast.makeText(context, "Could not copy tags", Toast.LENGTH_SHORT).show()
    }
}

private fun copyPostUrlWithFeedback(
    context: Context,
    post: Post,
    onPostUrlCopied: (Post) -> Unit,
) {
    if (copyPostUrlToClipboard(context, post)) {
        onPostUrlCopied(post)
        showClipboardCopyConfirmation(context, "Post URL copied")
    } else {
        Toast.makeText(context, "No post URL available", Toast.LENGTH_SHORT).show()
    }
}
