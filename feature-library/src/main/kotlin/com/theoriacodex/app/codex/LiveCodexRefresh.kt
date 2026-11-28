package com.theoriacodex.app.codex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

@Composable
fun ShowNewestAfterRefresh(owner: LiveCodexFeedOwner, scrollToStart: suspend () -> Unit) {
    val refreshes = owner.state.collectAsStateWithLifecycle().value.refreshes
    var shown by rememberSaveable { mutableIntStateOf(refreshes) }
    LaunchedEffect(refreshes) {
        val refreshed = refreshes > shown
        shown = refreshes
        if (refreshed) scrollToStart()
    }
}
