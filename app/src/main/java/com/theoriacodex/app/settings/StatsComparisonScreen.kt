package com.theoriacodex.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.statistics.CodexStatisticsSummary
import com.theoriacodex.app.statistics.ComparedPost
import com.theoriacodex.app.statistics.StatsShareFile
import com.theoriacodex.app.statistics.buildStatsShare
import com.theoriacodex.app.statistics.compareStats
import com.theoriacodex.app.viewer.decodeViewerPostId
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

private enum class ComparedPostView(val label: String) { BOTH("Both"), THEIRS("Theirs"), YOURS("Yours") }

/** Read-only: the other person's numbers live only as long as this screen, and never join your statistics. */
@Composable
internal fun StatsComparisonScreen(
    codices: List<CodexStatisticsSummary>,
    theirs: StatsShareFile,
    availableSources: Collection<SourceKey>,
    onOpenPost: (PostId) -> Unit,
    onBack: () -> Unit,
) {
    val comparison = remember(codices, theirs) { compareStats(buildStatsShare(codices), theirs) }
    val theirName = theirs.name ?: "Them"
    var view by rememberSaveable { mutableStateOf(ComparedPostView.BOTH) }
    SettingsPageColumn {
        SettingsHeader("You vs $theirName", onBack, SETTINGS_PATH)
        StatsCard {
            ComparedValue("Time watching", comparison.yours.watchingMs, comparison.theirs.watchingMs, theirName, ::formatStatsDuration)
            ComparedValue("Time browsing", comparison.yours.browsingMs, comparison.theirs.browsingMs, theirName, ::formatStatsDuration)
            ComparedValue("Codex opens", comparison.yours.entries, comparison.theirs.entries, theirName, Long::toString)
            ComparedValue("Post opens", comparison.yours.postOpens, comparison.theirs.postOpens, theirName, Long::toString)
        }
        StatsSectionTitle("Codices")
        StatsCard {
            if (comparison.codices.isEmpty()) StatsCaption("No Codex activity on either side yet.")
            comparison.codices.forEach { codex ->
                ComparedValue(codex.name, codex.yourWatchingMs, codex.theirWatchingMs, theirName, ::formatStatsDuration)
            }
        }
        StatsSectionTitle("Top posts")
        StatsCard {
            StatsSegments(ComparedPostView.entries, view, { it.label }) { view = it }
            val posts = when (view) {
                ComparedPostView.BOTH -> comparison.postsInCommon
                ComparedPostView.THEIRS -> comparison.theirTopPosts
                ComparedPostView.YOURS -> comparison.yourTopPosts
            }
            if (posts.isEmpty()) StatsCaption(if (view == ComparedPostView.BOTH) "No top posts in common yet." else "No top posts yet.")
            posts.forEach { post -> ComparedPostRow(post, theirName, availableSources, onOpenPost) }
        }
    }
}

@Composable
private fun ComparedValue(label: String, yours: Long, theirs: Long, theirName: String, format: (Long) -> String) {
    val peak = maxOf(yours, theirs).coerceAtLeast(1L).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ComparedBar("You", format(yours), yours / peak, MaterialTheme.colorScheme.primary)
        ComparedBar(theirName, format(theirs), theirs / peak, MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun ComparedBar(owner: String, value: String, fraction: Float, color: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            owner, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        StatsCaption(value)
    }
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(6.dp),
        color = color,
        drawStopIndicator = {},
    )
}

/** Their file can name a site that isn't set up here; those rows say so instead of failing when tapped. */
@Composable
private fun ComparedPostRow(
    post: ComparedPost,
    theirName: String,
    availableSources: Collection<SourceKey>,
    onOpenPost: (PostId) -> Unit,
) {
    val postId = remember(post.key) { decodeViewerPostId(post.key) }
    val openable = postId != null && postId.source in availableSources
    Column(
        Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
            .clickable(enabled = openable, role = Role.Button, onClickLabel = "Open post") { postId?.let(onOpenPost) },
    ) {
        Text(post.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        StatsCaption("You ${post.yourOpens} · $theirName ${post.theirOpens} opens")
        if (!openable) StatsCaption("This site isn't set up here")
    }
}
