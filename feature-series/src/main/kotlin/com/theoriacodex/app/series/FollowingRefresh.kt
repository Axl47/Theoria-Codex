package com.theoriacodex.app.series

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.theoriacodex.app.ui.components.PullRefreshBox
import com.theoriacodex.app.ui.components.RefreshMessagePill
import com.theoriacodex.app.ui.components.RefreshProgressPill
import com.theoriacodex.data.repository.CheckProgress
import com.theoriacodex.data.repository.FollowedCheck
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.domain.series.seriesKind

internal sealed interface RefreshStatus {
    data class Checking(val progress: CheckProgress) : RefreshStatus {
        val label: String get() = if (progress.total == 0) "Checking" else "${progress.done} of ${progress.total} series checked"
        val fraction: Float? get() = progress.done.toFloat().div(progress.total).takeIf { progress.total > 0 }
    }

    data class Done(val label: String) : RefreshStatus
}

internal fun pullLabel(words: EntryWords?): String = words?.let { "Check for new ${it.many}" } ?: "Check for updates"

internal fun refreshStatus(checking: CheckProgress?, finished: FollowedCheck?): RefreshStatus? = when {
    checking != null -> RefreshStatus.Checking(checking)
    finished != null && finished.refreshed > 0 -> RefreshStatus.Done(doneLabel(finished))
    else -> null
}

internal val FollowedCheck.refreshed: Int get() = checked - failed

private fun doneLabel(finished: FollowedCheck): String {
    val refreshed = "Refreshed ${finished.refreshed} series"
    return if (finished.updates.isEmpty()) refreshed else "$refreshed · ${foundLabel(finished.updates)}"
}

private fun foundLabel(found: List<SeriesUpdate>): String =
    found.groupBy { it.series.id.source.seriesKind.words }
        .entries.sortedBy { it.key }
        .joinToString(" · ") { (words, updates) -> words.new(updates.sumOf { it.newEntries.size }) }

@Composable
internal fun FollowingRefreshBox(
    checking: CheckProgress?,
    finished: FollowedCheck?,
    words: EntryWords?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val status = refreshStatus(checking, finished)
    PullRefreshBox(
        refreshing = checking != null,
        onRefresh = onRefresh,
        pullLabel = pullLabel(words),
        modifier = modifier,
        status = status?.let { { RefreshStatusPill(it) } },
        content = content,
    )
}

@Composable
internal fun RefreshStatusPill(status: RefreshStatus) = when (status) {
    is RefreshStatus.Checking -> RefreshProgressPill(status.label, status.fraction)
    is RefreshStatus.Done -> RefreshMessagePill(status.label)
}
