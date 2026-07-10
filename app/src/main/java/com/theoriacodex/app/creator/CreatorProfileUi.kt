package com.theoriacodex.app.creator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.theoriacodex.domain.model.canonicalHitomiArtistIdentity

fun supportsCreatorBrowsing(source: SourceKey): Boolean {
    return source == SourceKey.PIXIV ||
        source == SourceKey.GELBOORU ||
        source == SourceKey.HITOMI ||
        source == SourceKey.IWARA
}

fun browseableCreatorProfile(profile: CreatorProfile?): CreatorProfile? {
    if (profile == null || !supportsCreatorBrowsing(profile.source)) return null
    if (profile.uploadsQuery.isNullOrBlank()) return null
    if (profile.source == SourceKey.HITOMI && profile.canonicalHitomiArtistIdentity() == null) {
        return null
    }
    return profile
}

fun creatorButtonLabel(post: Post): String? {
    return creatorProfileActions(post).firstOrNull()?.label
}

internal data class CreatorProfileAction(
    val label: String,
    val profile: CreatorProfile?,
) {
    val requiresLegacyResolution: Boolean
        get() = profile == null
}

internal fun creatorProfileActions(post: Post): List<CreatorProfileAction> {
    if (!supportsCreatorBrowsing(post.id.source)) return emptyList()

    val explicitProfiles = post.creatorProfiles
        .mapNotNull(::browseableCreatorProfile)
        .distinct()
    if (explicitProfiles.isNotEmpty()) {
        return explicitProfiles.map { profile ->
            CreatorProfileAction(
                label = profile.displayName.trim(),
                profile = profile,
            )
        }.filter { action -> action.label.isNotBlank() }
    }

    if (post.creatorProfiles.isNotEmpty()) return emptyList()
    val fallbackLabel = post.creatorProfile?.displayName
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: post.authorName?.trim()?.takeIf(String::isNotBlank)
        ?: return emptyList()
    return listOf(CreatorProfileAction(label = fallbackLabel, profile = null))
}

@Composable
fun CreatorProfileActionButton(
    post: Post,
    onOpenProfile: (CreatorProfile) -> Unit,
    onOpenLegacyPost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = creatorProfileActions(post)
    if (actions.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (actions.size > 1) {
            Text(
                text = "Creators",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        actions.forEach { action ->
            CreatorProfileActionSurface(
                label = action.label,
                onClick = {
                    val profile = action.profile
                    if (profile != null) {
                        onOpenProfile(profile)
                    } else {
                        onOpenLegacyPost()
                    }
                },
            )
        }
    }
}

@Composable
private fun CreatorProfileActionSurface(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
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
