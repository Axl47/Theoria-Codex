package com.theoriacodex.app.ui

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
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
import com.theoriacodex.app.codex.markSavedSearchPostsSeen
import com.theoriacodex.app.codex.savedNewPosts
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.ui.components.FeedView
import com.theoriacodex.data.repository.CreatorFollowsRepository
import com.theoriacodex.data.repository.markKey
import com.theoriacodex.domain.model.Post
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
                follows.cancelAutomaticRefresh()
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

/**
 * Wraps a live collection's [header] so that once its feed has drawn its first page, the posts that page
 * showed (after the grid's display filters) are seen. A check that lands later, posts the filters hid,
 * posts only in later pages, and posts the feed never showed (offline, a failed load) stay new.
 */
internal fun markingNewPostsSeen(
    shell: TheoriaShell,
    codexId: String,
    loading: Boolean,
    header: (@Composable (FeedView, LazyStaggeredGridState) -> Unit)?,
): (@Composable (FeedView, LazyStaggeredGridState) -> Unit)? = header?.let { drawHeader ->
    { feed, grid ->
        drawHeader(feed, grid)
        val shown = feed.visiblePosts
        val rendered = !loading && shown.isNotEmpty() && codexId in newPostCodexIds
        LaunchedEffect(codexId, rendered) {
            if (!rendered) return@LaunchedEffect
            withFrameNanos {}
            shell.data.markNewPostsSeen(codexId, shown.mapTo(mutableSetOf()) { it.id.markKey() })
        }
    }
}

private val newPostCodexIds = setOf(FOLLOWED_CODEX_ID, SAVED_SEARCHES_CODEX_ID)

/** Marks [shown] ([markKey]s) seen in the Followed or Searches collection [codexId]; any other collection has no new posts. */
private suspend fun DataDependencies.markNewPostsSeen(codexId: String, shown: Set<String>) {
    when (codexId) {
        FOLLOWED_CODEX_ID -> CreatorFollowsRepository(settingsRepository).markSeen(shown)
        SAVED_SEARCHES_CODEX_ID -> uiRestoreRepository.markSavedSearchPostsSeen(shown)
    }
}

/**
 * Opens the Viewer on a Saved shelf's posts. Only the tapped post opens, so only it is seen; the
 * rest of the shelf stays new until the Viewer or the full collection shows it.
 */
internal fun TheoriaShell.openSavedShelfViewer(codexId: String, posts: List<Post>, index: Int) {
    val opened = posts.getOrNull(index)
    if (opened != null && codexId in newPostCodexIds) scope.launch { data.markNewPostsSeen(codexId, setOf(opened.id.markKey())) }
    openCodexViewer(codexId, posts, index)
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
