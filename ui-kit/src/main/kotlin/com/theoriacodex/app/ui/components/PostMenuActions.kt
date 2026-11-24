package com.theoriacodex.app.ui.components

import com.theoriacodex.domain.model.CreatorProfile
import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Source
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import com.theoriacodex.domain.model.Post

@Immutable
class PostMenuActions(
    val openOriginalPost: (url: String) -> Unit = {},
    val openInBrowser: (url: String) -> Unit = {},
    val findOnOtherSites: (Post) -> (() -> Unit)? = { null },
    val share: (url: String) -> Unit = {},
    val copyTags: (Post) -> Unit = {},
    val copyLink: (url: String) -> Unit = {},
    val openCreator: (CreatorProfile) -> Unit = {},
    val openPostCreator: (Post) -> Unit = {},
)

val LocalPostMenuActions = staticCompositionLocalOf { PostMenuActions() }

class PostMenuAction(val label: String, val icon: ImageVector, val run: () -> Unit)

private val Post.link: String? get() = pageUrl?.trim()?.takeIf(String::isNotBlank)

fun PostMenuActions.shareFor(post: Post): (() -> Unit)? = post.link?.let { url -> { share(url) } }

fun PostMenuActions.availableFor(post: Post): List<PostMenuAction> = listOfNotNull(
    post.originUrl?.let { url -> PostMenuAction("Open original post", Icons.Default.Source) { openOriginalPost(url) } },
    findOnOtherSites(post)?.let { find -> PostMenuAction("Find on other sites", Icons.Default.ImageSearch, find) },
    post.link?.let { url -> PostMenuAction("Open in browser", Icons.Default.Public) { openInBrowser(url) } },
    PostMenuAction("Copy tags", Icons.Default.ContentCopy) { copyTags(post) },
    post.link?.let { url -> PostMenuAction("Copy link", Icons.Default.Link) { copyLink(url) } },
)

fun sharePostLink(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
