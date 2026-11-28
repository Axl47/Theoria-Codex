package com.theoriacodex.app.ui

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
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.data.repository.CreatorFollowsRepository
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.markKey
import com.theoriacodex.domain.model.CreatorProfile
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

internal fun newPostsSeenHandler(data: DataDependencies, codexId: String): (suspend (Set<PostId>) -> Unit)? =
    if (codexId !in newPostCodexIds) null else { seen -> data.markNewPostsSeen(codexId, seen.mapTo(mutableSetOf()) { it.markKey() }) }

private val newPostCodexIds = setOf(FOLLOWED_CODEX_ID, SAVED_SEARCHES_CODEX_ID)

internal class NewPostsSeen(
    private val keys: Set<String>,
    private val followed: CreatorProfile?,
    private val mark: suspend (Set<PostId>, CreatorProfile?) -> Unit,
) {
    val onSeen: suspend (Set<PostId>) -> Unit = { seen -> mark(seen, followed) }

    fun among(posts: List<Post>): Set<PostId> =
        posts.mapNotNullTo(mutableSetOf()) { post -> post.id.takeIf { it.markKey() in keys || it.source == followed?.source } }

    fun withoutFollowed(): NewPostsSeen = if (followed == null) this else NewPostsSeen(keys, null, mark)
}

@Composable
internal fun rememberNewPostsSeen(data: DataDependencies, followed: CreatorProfile? = null): NewPostsSeen {
    val keys = rememberSavedNewPosts(data).values.flatMapTo(mutableSetOf()) { it }
    return remember(data, followed, keys) { NewPostsSeen(keys, followed) { seen, creator -> data.markPostsSeen(seen, creator) } }
}

private suspend fun DataDependencies.markPostsSeen(seen: Set<PostId>, followed: CreatorProfile?) {
    followed?.let { creator ->
        CreatorFollowsRepository(settingsRepository).markShown(creator, seen.filter { it.source == creator.source }.map { it.sourcePostId })
    }
    val keys = seen.mapTo(mutableSetOf()) { it.markKey() }
    newPostCodexIds.forEach { markNewPostsSeen(it, keys) }
}

private suspend fun DataDependencies.markNewPostsSeen(codexId: String, shown: Set<String>) {
    when (codexId) {
        FOLLOWED_CODEX_ID -> CreatorFollowsRepository(settingsRepository).markSeen(shown)
        SAVED_SEARCHES_CODEX_ID -> uiRestoreRepository.markSavedSearchPostsSeen(shown)
    }
}

internal suspend fun DataDependencies.markViewedNewPost(post: Post, session: ViewerSession?) {
    val key = post.id.markKey()
    if (newPostsCollection(session?.context) != null) {
        viewedNewPosts.update { viewed -> viewed + newPostCodexIds.associateWith { viewed[it].orEmpty() + key } }
    }
    newPostCodexIds.forEach { markNewPostsSeen(it, setOf(key)) }
}

private class OpenedNewPosts(val markKeys: Set<String>) : ViewModel()

@Composable
internal fun rememberNewPostMarkers(
    data: DataDependencies,
    codexId: String,
    posts: List<Post>,
    live: Boolean = false,
    newOnOpen: () -> Set<String>,
): Set<PostId> {
    if (live) {
        val current = newOnOpen()
        return remember(posts, current) { posts.markedBy(current, emptySet()) }
    }
    val opened = viewModel(key = "opened-new-posts:$codexId") { OpenedNewPosts(newOnOpen()) }.markKeys
    val viewed = data.viewedNewPosts.collectAsStateWithLifecycle().value[codexId].orEmpty()
    return remember(posts, opened, viewed) { posts.markedBy(opened, viewed) }
}

private fun List<Post>.markedBy(marked: Set<String>, viewed: Set<String>): Set<PostId> =
    if (marked.isEmpty()) emptySet()
    else mapNotNullTo(mutableSetOf()) { post -> post.id.takeIf { id -> id.markKey().let { it in marked && it !in viewed } } }

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
