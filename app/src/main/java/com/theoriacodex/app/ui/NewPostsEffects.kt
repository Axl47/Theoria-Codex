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
import com.theoriacodex.app.codex.SAVED_SEARCHES_CODEX_ID
import com.theoriacodex.app.codex.SavedSearchBranch
import com.theoriacodex.app.codex.SavedSearchFeedLoader
import com.theoriacodex.app.codex.SavedSearchNewPostsChecker
import com.theoriacodex.app.codex.markSavedSearchesSeen
import com.theoriacodex.app.codex.savedNewPosts
import com.theoriacodex.app.di.DataDependencies
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Checks followed creators and saved searches for new posts whenever the app comes to the
 * foreground, once the first frame is drawn and only when the last check is stale. Leaving the app
 * cancels checks in flight.
 */
@Composable
internal fun NewPostsCheckEffect(shell: TheoriaShell) {
    val follows = shell.owners.follows
    val searches = remember(shell) { savedSearchChecker(shell) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(follows, searches, lifecycle) {
        withFrameNanos {}
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val now = System.currentTimeMillis()
            launch { searches.checkIfStale(shell.data.savedSearches.observeSavedSearches().first(), now) }
            try {
                follows.refreshIfStale(now)
                awaitCancellation()
            } finally {
                follows.cancelRefresh()
            }
        }
    }
}

/** Saved searches run read-only, on the sources and settings their collection uses. */
private fun savedSearchChecker(shell: TheoriaShell): SavedSearchNewPostsChecker {
    val loader = SavedSearchFeedLoader(shell.features.search)
    return SavedSearchNewPostsChecker(shell.data.uiRestoreRepository) { entry ->
        val runtime = shell.data.settingsRepository.observeSettings().first().runtime
        val sources = shell.sources.availableSources.value
        loader.updateEnvironment(sources, runtime.copy(enabledSources = runtime.enabledSources.intersect(sources)))
        loader.load(SavedSearchBranch(entry), retry = false).takeIf { it.error == null }?.posts
    }
}

/** Opening the Followed or Searches feed marks its latest posts seen. */
@Composable
internal fun MarkNewPostsSeenEffect(shell: TheoriaShell, codexId: String) {
    when (codexId) {
        FOLLOWED_CODEX_ID -> LaunchedEffect(codexId) { shell.owners.follows.markLatestSeen() }
        SAVED_SEARCHES_CODEX_ID -> LaunchedEffect(codexId) { shell.data.uiRestoreRepository.markSavedSearchesSeen() }
    }
}

/** [savedNewPosts] as it changes, counting only searches that are still saved; empty while nothing is new. */
@Composable
internal fun rememberSavedNewPosts(data: DataDependencies): Map<String, Set<String>> =
    remember(data) {
        combine(
            data.settingsRepository.observeSettings(),
            data.savedSearches.observeSavedSearches(),
            data.uiRestoreRepository.observeSavedSearchNewPosts(),
        ) { settings, searches, news ->
            val saved = searches.mapTo(mutableSetOf()) { it.id }
            savedNewPosts(settings.followedCreators, news.marks.filterKeys { it in saved }.values)
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(emptyMap()).value
