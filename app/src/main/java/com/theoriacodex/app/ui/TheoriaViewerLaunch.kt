package com.theoriacodex.app.ui

import com.theoriacodex.app.related.relatedViewerContext
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.viewer.ViewerRelatedBinding
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.launch

/** A feed result opened in the Viewer: its posts, where to start, and whether the Viewer keeps following the feed. */
internal data class FeedViewerLaunch(
    val posts: List<Post>,
    val context: ViewerLaunchContext,
    val liveSearchBinding: Boolean,
    val visibilityFilters: SearchVisibilityFilters,
)

/**
 * Opens [session] in the Viewer; every Viewer launch ends here. The session is retained for the Viewer
 * destination to claim, then the Viewer is navigated to. Launches differ in two ways, which each caller states:
 * - [saveContext] saves the launch context, so a Viewer recreated after process death restores its place from it.
 *   Search saves its own when it opens a result; Creator and related launches leave the saved context as it was.
 * - [singleTop] lets a launch reuse a Viewer already on top instead of stacking another.
 */
internal suspend fun TheoriaShell.launchViewer(session: ViewerSession, saveContext: Boolean, singleTop: Boolean) {
    features.offlineMedia.store.load()
    viewerSessions.retain(session)
    if (saveContext) data.uiRestoreRepository.setViewerLaunchContext(session.context)
    navController.navigate(AppRoute.Viewer) { launchSingleTop = singleTop }
}

/** Opens a feed result, binding the Viewer's like action and related strip to the query the feed shows at this tap. */
internal suspend fun TheoriaShell.openFeedViewer(
    source: ViewerStreamSource,
    launch: FeedViewerLaunch,
    saveContext: Boolean,
    singleTop: Boolean,
    saveQuery: Query? = null,
) {
    val likeAction = feedLikeAction(source)
    val relatedBinding = ViewerRelatedBinding(source, routes.feed(source)?.viewerFeed?.queryHash.orEmpty())
    val session = viewerRouteWorkflow.prepareResumableSession(
        ViewerSession(
            posts = launch.posts,
            context = launch.context,
            liveSearchBinding = launch.liveSearchBinding,
            searchVisibilityFilters = launch.visibilityFilters,
            onToggleLike = likeAction,
            saveQuery = saveQuery,
            relatedBinding = relatedBinding,
        ),
    )
    launchViewer(session, saveContext, singleTop)
}

/** Opens the Viewer from a Codex; resumes at the saved position when this post has one. */
internal fun TheoriaShell.openCodexViewer(codexId: String, visiblePosts: List<Post>, index: Int) {
    openStaticViewer(visiblePosts, ViewerLaunchContext("codex:$codexId", index, ViewerStreamSource.CODEX, 0), saveContext = true)
}

/** Opens a related shelf no feed owner pages, such as a live Codex's; like other related streams it is not restored. */
internal fun TheoriaShell.openRelatedViewer(seed: Post, shelfPosts: List<Post>, index: Int) {
    openStaticViewer(shelfPosts, relatedViewerContext(seed, index), saveContext = false)
}

private fun TheoriaShell.openStaticViewer(visiblePosts: List<Post>, context: ViewerLaunchContext, saveContext: Boolean) {
    scope.launch {
        val session = viewerRouteWorkflow.prepareResumableSession(
            ViewerSession(posts = visiblePosts, context = context, liveSearchBinding = false),
        )
        launchViewer(session, saveContext, singleTop = false)
    }
}

/** Opens one Recents section in the Viewer, resuming each post unless asked to start over. */
internal fun TheoriaShell.openRecentSection(
    entries: List<RecentPostEntry>,
    index: Int,
    section: RecentPostSection,
    startOver: Boolean = false,
) {
    val posts = entries.map { entry -> entry.post }
    if (posts.isEmpty()) return
    val context = ViewerLaunchContext(
        queryHash = "recents:${section.name.lowercase()}",
        startIndex = index.coerceIn(0, posts.lastIndex),
        streamSource = ViewerStreamSource.RECENTS,
        scrollOffsetHint = 0,
        recentsSection = section,
    )
    scope.launch {
        val session = viewerRouteWorkflow.prepareResumableSession(
            ViewerSession(posts = posts, context = context),
            startOver = startOver,
        )
        launchViewer(session, saveContext = true, singleTop = false)
    }
}

/** Replaces any open Viewer with a session holding one post, or reports that the post could not be loaded. */
internal suspend fun TheoriaShell.openSinglePostViewer(queryHash: String, initialMediaIndex: Int = 0, load: suspend () -> Post?) {
    val post = runCatchingPreservingCancellation { load() }.getOrNull()
    if (post == null) {
        snackbarHostState.showSnackbar("This post could not be opened. Try again.")
        return
    }
    openViewerSession(queryHash, listOf(post), initialMediaIndex)
}

/**
 * Replaces any open Viewer, with every level of its trail, with a fresh session over [posts], which also teach the file
 * mirror index.
 */
internal suspend fun TheoriaShell.openViewerSession(queryHash: String, posts: List<Post>, initialMediaIndex: Int = 0) {
    data.fileMirrors.learn(posts)
    if (navigation.currentRoute == AppRoute.Viewer) {
        routes.viewer.current?.clearSession(retainPresentation = true)
        viewerSessions.clear()
        // A trail stacks Viewer entries; popping to the route alone would leave the levels under the top one.
        do {
            val popped = navController.popBackStack()
        } while (popped && navController.currentDestination?.route == AppRoute.Viewer)
    }
    val session = ViewerSession(
        posts = posts,
        context = ViewerLaunchContext(queryHash, 0, ViewerStreamSource.RECENTS, 0),
        liveSearchBinding = false,
        initialMediaIndices = posts.firstOrNull()?.let { first -> mapOf(first.id to initialMediaIndex) }.orEmpty(),
    )
    launchViewer(session, saveContext = true, singleTop = false)
}
