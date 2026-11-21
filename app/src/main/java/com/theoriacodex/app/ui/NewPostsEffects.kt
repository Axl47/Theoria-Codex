package com.theoriacodex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.theoriacodex.app.codex.FOLLOWED_CODEX_ID
import com.theoriacodex.app.codex.savedNewCounts
import com.theoriacodex.app.creator.CreatorFollowsViewModel
import com.theoriacodex.app.di.DataDependencies
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Checks followed creators for new posts whenever the app comes to the foreground, once the first
 * frame is drawn and only when the last check is stale. Leaving the app cancels a check in flight.
 */
@Composable
internal fun NewPostsCheckEffect(follows: CreatorFollowsViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(follows, lifecycle) {
        withFrameNanos {}
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                follows.refreshIfStale(System.currentTimeMillis())
                awaitCancellation()
            } finally {
                follows.cancelRefresh()
            }
        }
    }
}

/** Opening the Followed feed marks its creators' latest posts seen. */
@Composable
internal fun MarkNewPostsSeenEffect(follows: CreatorFollowsViewModel, codexId: String) {
    if (codexId == FOLLOWED_CODEX_ID) LaunchedEffect(follows) { follows.markLatestSeen() }
}

/** [savedNewCounts] as it changes; empty while nothing is new. */
@Composable
internal fun rememberSavedNewCounts(data: DataDependencies): Map<String, Int> =
    remember(data) {
        data.settingsRepository.observeSettings().map { savedNewCounts(it.followedCreators) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(emptyMap()).value
