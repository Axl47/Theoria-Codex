package com.theoriacodex.app.codex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.components.PullRefreshBox
import com.theoriacodex.app.ui.components.RefreshProgressPill

@Composable
fun LiveCodexRefreshBox(
    owners: Collection<LiveCodexFeedOwner>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val refreshing = owners.map { owner -> key(owner) { owner.state.collectAsStateWithLifecycle().value.refreshing } }.any { it }
    PullRefreshBox(
        refreshing = refreshing,
        onRefresh = { owners.forEach(LiveCodexFeedOwner::refresh) },
        pullLabel = "Check for new posts",
        modifier = modifier,
        status = if (refreshing) ({ RefreshProgressPill("Checking", null) }) else null,
        content = content,
    )
}
