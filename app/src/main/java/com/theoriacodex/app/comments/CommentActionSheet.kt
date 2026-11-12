package com.theoriacodex.app.comments

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.copyTextToClipboard
import com.theoriacodex.app.ui.components.ActionMenuDetailText
import com.theoriacodex.app.ui.components.ActionMenuHeader
import com.theoriacodex.app.ui.components.ActionMenuRow
import com.theoriacodex.app.ui.components.ActionMenuSheet
import com.theoriacodex.app.ui.components.HeaderLink
import com.theoriacodex.app.ui.components.SourceImage
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.SourceKey

/**
 * What holding a comment offers: its text, and its [link] (null when the post has no page URL to build one on).
 * The author's name opens their profile when [author] says where it leads; every action closes the sheet.
 */
@Composable
internal fun CommentActionSheet(
    source: SourceKey,
    comment: PostComment,
    link: String?,
    author: AuthorDestination?,
    view: CommentsView,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    fun dismissThen(action: () -> Unit) {
        onDismiss()
        action()
    }
    fun copy(label: String, text: String, failure: String) {
        if (!copyTextToClipboard(context, label, text)) Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
        onDismiss()
    }
    val firstLine = comment.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    ActionMenuSheet(onDismiss) {
        ActionMenuHeader(
            title = comment.authorName,
            titleLink = author?.let { destination -> HeaderLink(comment.authorName) { dismissThen { view.onOpenAuthor(destination) } } },
            image = { modifier ->
                val avatar = comment.authorAvatarUrl
                if (avatar != null) {
                    SourceImage(source, avatar, 48.dp, RoundedCornerShape(12.dp), modifier)
                } else {
                    Box(modifier, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            details = { firstLine?.let { ActionMenuDetailText(it, singleLine = true) } },
        )
        if (comment.body.isNotBlank()) {
            ActionMenuRow(Icons.Default.ContentCopy, "Copy text") { copy("comment", comment.body, "Could not copy text") }
        }
        if (link != null) {
            ActionMenuRow(Icons.Default.Link, "Copy link") { copy("comment_link", link, "Could not copy link") }
            ActionMenuRow(Icons.AutoMirrored.Filled.OpenInNew, "Open in browser") { dismissThen { view.onOpenUrl(link) } }
        }
    }
}
