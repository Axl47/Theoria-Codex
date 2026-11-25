package com.theoriacodex.app.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.comments.CommentCount
import com.theoriacodex.app.ui.motion.LikedHeartColor
import com.theoriacodex.app.ui.motion.likePop
import com.theoriacodex.app.ui.motion.rememberLikeFeedback

internal val VIEWER_DOCK_HEIGHT = 56.dp

internal data class ViewerMenuItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)

internal class ViewerDockActions(
    val liked: Boolean,
    val onToggleLike: (() -> Unit)?,
    val commentCount: CommentCount?,
    val onInfo: () -> Unit,
    val moreExpanded: Boolean,
    val onMoreExpandedChange: (Boolean) -> Unit,
    val moreItems: List<ViewerMenuItem>,
    val overview: ViewerOverviewToggle?,
    val playbackSettings: (@Composable () -> Unit)?,
)

internal class ViewerOverviewToggle(val visible: Boolean, val onToggle: () -> Unit)

@Composable
internal fun ViewerDock(actions: ViewerDockActions, modifier: Modifier = Modifier, save: @Composable RowScope.() -> Unit) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Row(
            modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(VIEWER_DOCK_HEIGHT)
                .padding(horizontal = 4.dp).testTag("viewer-dock"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerLikeAction(actions.liked, actions.onToggleLike)
            ViewerInfoAction(actions.commentCount, actions.onInfo)
            actions.playbackSettings?.invoke()
            actions.overview?.let { ViewerOverviewAction(it) }
            if (actions.moreItems.isNotEmpty()) ViewerMoreMenu(actions.moreExpanded, actions.onMoreExpandedChange, actions.moreItems)
            Spacer(Modifier.weight(1f))
            save()
        }
    }
}

@Composable
private fun ViewerLikeAction(liked: Boolean, onToggleLike: (() -> Unit)?) {
    if (onToggleLike == null) return
    val feedback = rememberLikeFeedback()
    IconButton(onClick = {
        feedback.tapped(liked)
        onToggleLike()
    }) {
        Icon(
            imageVector = if (liked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (liked) "Unlike post" else "Like post",
            tint = if (liked) LikedHeartColor else Color.White,
            modifier = Modifier.likePop(feedback),
        )
    }
}

@Composable
private fun ViewerInfoAction(count: CommentCount?, onClick: () -> Unit) {
    val label = count?.label
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp)
            .clearAndSetSemantics { contentDescription = count?.let { "Info, ${it.description(unknown = "comments")}" } ?: "Info" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null)
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun ViewerOverviewAction(toggle: ViewerOverviewToggle) {
    IconButton(onClick = toggle.onToggle) {
        ViewerAccentIcon(Icons.Default.Collections, if (toggle.visible) "Close media overview" else "Open media overview", accented = toggle.visible)
    }
}

@Composable
private fun ViewerMoreMenu(expanded: Boolean, onExpandedChange: (Boolean) -> Unit, items: List<ViewerMenuItem>) {
    Box {
        IconButton(onClick = { onExpandedChange(!expanded) }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        onExpandedChange(false)
                        item.onClick()
                    },
                    leadingIcon = { Icon(item.icon, contentDescription = null) },
                )
            }
        }
    }
}

internal fun viewerMoreItems(onChooseCodex: (() -> Unit)?, onPictureInPicture: (() -> Unit)?, extra: List<ViewerMenuItem>): List<ViewerMenuItem> =
    listOfNotNull(
        onChooseCodex?.let { ViewerMenuItem("Save to…", Icons.Outlined.BookmarkAdd, it) },
        onPictureInPicture?.let { ViewerMenuItem("Picture-in-picture", Icons.Default.PictureInPictureAlt, it) },
    ) + extra
