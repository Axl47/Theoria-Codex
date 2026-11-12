package com.theoriacodex.app.creator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.SourceImage
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.tags.compactTagCount
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val AVATAR_SIZE = 48.dp
private const val BIO_COLLAPSED_LINES = 3

/** The header's muted lines: [stats] always (it opens with the source), [secondary] only when the source offers its facts. */
internal data class AccountHeaderText(val stats: String, val secondary: String?)

/**
 * Builds the header lines from whatever [details] holds. A favorites count of 0 is left out: sites
 * report 0 for private favorites, which would read as "no favorites".
 */
internal fun accountHeaderText(
    source: SourceKey,
    details: AccountDetails?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): AccountHeaderText {
    fun count(n: Int, noun: String) = "${compactTagCount(n)} $noun${if (n == 1) "" else "s"}"
    val stats = listOfNotNull(
        source.displayName(),
        details?.uploadCount?.let { count(it, "upload") },
        details?.favoriteCount?.takeIf { it > 0 }?.let { count(it, "favorite") },
    )
    val joined = details?.joinedAtEpochMs?.let { epochMs ->
        "Joined " + DateTimeFormatter.ofPattern("MMM yyyy", locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))
    }
    val secondary = listOfNotNull(joined, details?.followerCount?.let { count(it, "follower") })
    return AccountHeaderText(stats.joinToString(" · "), secondary.takeIf { it.isNotEmpty() }?.joinToString(" · "))
}

internal val AccountFeed.label: String
    get() = when (this) {
        AccountFeed.UPLOADS -> "Uploads"
        AccountFeed.FAVORITES -> "Favorites"
    }

/** Avatar, facts and Follow for an account, with the bio under them. Facts fill in as [details] arrives. */
@Composable
internal fun AccountHeader(
    creator: CreatorProfile,
    details: AccountDetails?,
    followed: Boolean,
    followEnabled: Boolean,
    onToggleFollow: () -> Unit,
) {
    val text = remember(creator.source, details) { accountHeaderText(creator.source, details) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccountAvatar(creator, details?.avatarUrl)
            Column(Modifier.weight(1f)) {
                Text(
                    text.stats,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                text.secondary?.let { secondary ->
                    Text(
                        secondary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Follow tracks the creator's uploads search, so an account without one cannot be followed.
            if (!creator.uploadsQuery.isNullOrBlank()) {
                CreatorFollowButton(followed = followed, onClick = onToggleFollow, enabled = followEnabled)
            }
        }
        details?.bio?.trim()?.takeIf(String::isNotEmpty)?.let { bio -> AccountBio(bio) }
    }
}

/** The bio, clipped to three lines until tapped. */
@Composable
private fun AccountBio(bio: String) {
    var expanded by remember(bio) { mutableStateOf(false) }
    Text(
        bio,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else BIO_COLLAPSED_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    )
}

/** The account's picture, requested with the source's media headers; a circle with the name's initial when there is none. */
@Composable
private fun AccountAvatar(creator: CreatorProfile, url: String?) {
    Box(
        modifier = Modifier.size(AVATAR_SIZE).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Text(
                creator.displayName.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SourceImage(creator.source, url, AVATAR_SIZE, CircleShape)
        }
    }
}

/** Tabs for an account's feeds; nothing when there is only one. */
@Composable
internal fun AccountFeedTabs(tabs: List<AccountFeed>, selected: AccountFeed, onSelect: (AccountFeed) -> Unit) {
    if (tabs.size < 2) return
    PrimaryTabRow(selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0), containerColor = Color.Transparent) {
        tabs.forEach { feed ->
            Tab(selected = feed == selected, onClick = { onSelect(feed) }, text = { Text(feed.label) })
        }
    }
}
