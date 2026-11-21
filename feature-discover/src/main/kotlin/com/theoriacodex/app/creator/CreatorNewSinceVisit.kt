package com.theoriacodex.app.creator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

/** The post ids [creator]'s follow had seen when its page opened, before this visit recorded more. */
data class CreatorSeenSnapshot(val creator: CreatorProfile, val seenPostIds: Set<String>)

/**
 * The post the "new since last visit" divider goes before: the first of [posts] that [seenPostIds] holds, when
 * unseen posts come before it. Null when no loaded post was seen (the rest are unloaded, not new) or the first was.
 */
internal fun newSinceVisitBoundary(posts: List<Post>, creator: CreatorProfile, seenPostIds: Set<String>): PostId? {
    val index = posts.indexOfFirst { it.id.source == creator.source && it.id.sourcePostId in seenPostIds }
    return posts.getOrNull(index)?.id?.takeIf { index > 0 }
}

/** [newSinceVisitBoundary] over the shown [posts] of the uploads [feed]; null on other feeds or when [seenAtOpen] is. */
@Composable
internal fun rememberNewSinceVisit(feed: AccountFeed, creator: CreatorProfile, posts: List<Post>, seenAtOpen: Set<String>?): PostId? =
    remember(feed, creator, posts, seenAtOpen) {
        seenAtOpen?.takeIf { feed == AccountFeed.UPLOADS }?.let { newSinceVisitBoundary(posts, creator, it) }
    }

/** The full-width line between a followed creator's new posts and those already seen. */
@Composable
internal fun NewSinceVisitDivider() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text("Already seen", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f))
    }
}
