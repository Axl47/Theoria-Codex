package com.theoriacodex.app.ui

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.codex.FOLLOWED_CODEX_ID
import com.theoriacodex.app.codex.SAVED_SEARCHES_CODEX_ID
import com.theoriacodex.app.codex.SavedSearchBranch
import com.theoriacodex.app.codex.SavedSearchFeedLoader
import com.theoriacodex.app.codex.SavedSearchNewPostsChecker
import com.theoriacodex.app.codex.markSavedSearchPostsSeen
import com.theoriacodex.app.codex.savedNewPosts
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.statistics.statisticsCodexId
import com.theoriacodex.app.ui.components.FeedView
import com.theoriacodex.app.ui.components.SeenPostsEffect
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.data.repository.CreatorFollowsRepository
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.markKey
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Composable
internal fun NewPostsCheckEffect(shell: TheoriaShell) {
    val follows = shell.owners.follows
    val searches = remember(shell) { savedSearchChecker(shell) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(follows, searches, lifecycle) {
        withFrameNanos {}
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val now = System.currentTimeMillis()
            launch { searches.checkIfStale(now) }
            try {
                follows.refreshIfStale(now)
                awaitCancellation()
            } finally {
                follows.cancelAutomaticRefresh()
            }
        }
    }
}

private fun savedSearchChecker(shell: TheoriaShell): SavedSearchNewPostsChecker {
    val loader = SavedSearchFeedLoader(shell.features.search)
    val saved = shell.data.savedSearches.observeSavedSearches()
    return SavedSearchNewPostsChecker(shell.data.uiRestoreRepository, { saved.first() }) { entry ->
        val runtime = shell.data.settingsRepository.observeSettings().first().runtime
        val sources = shell.sources.availableSources.value
        loader.updateEnvironment(sources, runtime.copy(enabledSources = runtime.enabledSources.intersect(sources)))
        loader.load(SavedSearchBranch(entry), retry = false).takeIf { it.error == null }?.posts
    }
}

internal fun markingNewPostsSeen(
    data: DataDependencies,
    codexId: String,
    newPostIds: Set<PostId>,
    header: (@Composable (FeedView, LazyStaggeredGridState) -> Unit)?,
): (@Composable (FeedView, LazyStaggeredGridState) -> Unit)? = header?.takeIf { codexId in newPostCodexIds }?.let { drawHeader ->
    { feed, grid ->
        drawHeader(feed, grid)
        SeenPostsEffect(grid, feed.visiblePosts) { seen ->
            val keys = seen.filter { it in newPostIds }.mapTo(mutableSetOf()) { it.markKey() }
            if (keys.isNotEmpty()) data.markNewPostsSeen(codexId, keys)
        }
    }
}

private val newPostCodexIds = setOf(FOLLOWED_CODEX_ID, SAVED_SEARCHES_CODEX_ID)

private suspend fun DataDependencies.markNewPostsSeen(codexId: String, shown: Set<String>) {
    when (codexId) {
        FOLLOWED_CODEX_ID -> CreatorFollowsRepository(settingsRepository).markSeen(shown)
        SAVED_SEARCHES_CODEX_ID -> uiRestoreRepository.markSavedSearchPostsSeen(shown)
    }
}

internal suspend fun DataDependencies.markViewedNewPost(post: Post, session: ViewerSession?) {
    val collection = newPostsCollection(session?.context) ?: return
    val key = post.id.markKey()
    viewedNewPosts.update { viewed -> viewed + (collection to viewed[collection].orEmpty() + key) }
    markNewPostsSeen(collection, setOf(key))
}

private class OpenedNewPosts(val markKeys: Set<String>) : ViewModel()

@Composable
internal fun rememberNewPostMarkers(
    data: DataDependencies,
    codexId: String,
    posts: List<Post>,
    newOnOpen: () -> Set<String>,
): Set<PostId> {
    val opened = viewModel(key = "opened-new-posts:$codexId") { OpenedNewPosts(newOnOpen()) }.markKeys
    val viewed = data.viewedNewPosts.collectAsStateWithLifecycle().value[codexId].orEmpty()
    return remember(posts, opened, viewed) {
        if (opened.isEmpty()) return@remember emptySet()
        posts.mapNotNullTo(mutableSetOf()) { post ->
            post.id.takeIf { id -> id.markKey().let { key -> key in opened && key !in viewed } }
        }
    }
}

internal fun newPostsCollection(context: ViewerLaunchContext?): String? = statisticsCodexId(context)?.takeIf { it in newPostCodexIds }

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
