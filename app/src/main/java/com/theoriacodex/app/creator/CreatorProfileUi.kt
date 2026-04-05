package com.theoriacodex.app.creator

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey

fun supportsCreatorBrowsing(source: SourceKey): Boolean {
    return source == SourceKey.PIXIV || source == SourceKey.GELBOORU
}

fun browseableCreatorProfile(profile: CreatorProfile?): CreatorProfile? {
    if (profile == null || !supportsCreatorBrowsing(profile.source)) return null
    return profile.takeIf { !it.uploadsQuery.isNullOrBlank() }
}

fun creatorButtonLabel(post: Post): String? {
    if (!supportsCreatorBrowsing(post.id.source)) return null
    val creatorName = browseableCreatorProfile(post.creatorProfile)?.displayName
        ?: post.creatorProfile?.displayName
        ?: post.authorName
    return creatorName?.trim()?.takeIf { it.isNotBlank() }
}

@Composable
fun CreatorProfileActionButton(
    post: Post,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = creatorButtonLabel(post) ?: return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
