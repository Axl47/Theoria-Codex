package com.theoriacodex.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.theoriacodex.app.codex.LocalQuickSave
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.triage.TriageCardSupport
import com.theoriacodex.app.triage.TriageDeckCallbacks
import com.theoriacodex.app.triage.TriageDeckScreen
import com.theoriacodex.app.triage.TriageDeckViewModel
import com.theoriacodex.app.triage.TriageFeed
import com.theoriacodex.app.ui.components.feedDurations
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import com.theoriacodex.app.ui.navigation.TriageOpener
import com.theoriacodex.app.ui.navigation.TriageRequest
import com.theoriacodex.app.ui.routes.BrowsingDestinationStateBoundary
import com.theoriacodex.app.ui.routes.rememberMediaDurationRouteBinding
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.launch

/** [AppRoute.Triage]'s arguments. */
internal val triageArguments: List<NamedNavArgument> = listOf(
    navArgument("source") { type = NavType.StringType },
    navArgument("feedKey") { type = NavType.StringType; defaultValue = "" },
)

/** The [TriageRequest] this back stack entry was opened with; an unknown source reads as Search. */
internal fun NavBackStackEntry.triageRequest(): TriageRequest {
    val source = arguments?.getString("source")
    return TriageRequest(
        source = ViewerStreamSource.entries.firstOrNull { it.name == source } ?: ViewerStreamSource.SEARCH,
        feedKey = arguments?.getString("feedKey").orEmpty(),
    )
}

/**
 * Opens the triage deck by pushing [AppRoute.Triage]. A feed's menu calls it with the feed's own source and key:
 * Search, For You and a Creator page with their query hash, a Codex or Likes with `codex:<id>`.
 */
@Composable
internal fun rememberTriageOpener(shell: TheoriaShell): TriageOpener = remember(shell) {
    TriageOpener { request -> shell.navController.navigate(AppRoute.triage(request)) }
}

/** The triage deck over [request]'s feed; with no feed to work through, such as after process death, it closes. */
@Composable
internal fun TriageDestination(shell: TheoriaShell, request: TriageRequest) {
    // The deck's owner keeps the feed it was built with, so this runs again only to find nothing.
    val feed = remember(request) { shell.triageFeed(request) }
    if (feed == null) {
        LaunchedEffect(request) { shell.navController.popBackStack() }
        Box(Modifier.fillMaxSize())
        return
    }
    val query = shell.triageQuery(request)
    val quickSave = LocalQuickSave.current
    val owner = viewModel<TriageDeckViewModel> {
        TriageDeckViewModel(feed, ShellTriageActions(shell, request.source), quickSave, query, request.source == ViewerStreamSource.FOR_YOU)
    }
    val deck by owner.state.collectAsStateWithLifecycle()
    BrowsingDestinationStateBoundary(shell.data, shell.sources) { state ->
        val duration = rememberMediaDurationRouteBinding(
            coordinator = shell.features.mediaDurationCoordinator,
            routeName = "triage",
            ownerKey = "triage-duration-owner",
            contentIdentity = request.feedKey,
            posts = deck.posts,
            resolveInBackground = false,
        )
        val marks = state.feedMarks(shell.pendingLikePostIds[state.activeProfile.profileId].orEmpty())
        // Remembered, so the cards' environment is not rebuilt (and every card recomposed) by each state change.
        val onToggleLike = remember(shell, request.source) { { post: Post -> shell.feedLikeAction(request.source)(post) } }
        val resolvePostById = remember(shell, request.source) {
            suspend { postId: PostId -> shell.viewerRouteWorkflow.resolvePost(postId, request.source) }
        }
        val support = TriageCardSupport(
            environment = rememberFeedCardEnvironment(
                shell.sources.pixivUgoiraClient, marks.liked + marks.pendingLikes, emptyMap(),
                duration.owner.feedDurations(duration.states, resolveInBackground = false),
                onToggleLike, resolvePostById,
            ),
            resolvePostById = resolvePostById,
            recoverPostMedia = shell.features.search::recoverPostMedia,
        )
        TriageDeckScreen(
            viewModel = owner,
            query = query,
            support = support,
            callbacks = TriageDeckCallbacks(
                onOpenPost = { posts, index -> shell.openTriagePost(request, query, posts, index) },
                onOpenSavePicker = { post ->
                    shell.requestSaveToCodex(post, fromForYou = request.source == ViewerStreamSource.FOR_YOU, searchQuery = query)
                },
                onClose = { shell.navController.popBackStack() },
            ),
        )
    }
}

/** Search is the one feed whose query the deck can read; the others rank saves and hide tags without one. */
private fun TheoriaShell.triageQuery(request: TriageRequest): Query? =
    if (request.source == ViewerStreamSource.SEARCH) routes.search.current?.state?.value?.query?.applied else null

/**
 * Opens the Viewer on the deck's post [index]. A live feed's Viewer keeps following its owner, as a tap on that
 * feed's own grid does, so paging in the deck and in the Viewer share one feed.
 */
private fun TheoriaShell.openTriagePost(request: TriageRequest, query: Query?, posts: List<Post>, index: Int) {
    request.codexId()?.let { codexId -> return openCodexViewer(codexId, posts, index) }
    val owner = routes.feed(request.source) ?: return
    val context = ViewerLaunchContext(owner.viewerFeed.queryHash.orEmpty(), index, request.source, scrollOffsetHint = 0)
    scope.launch {
        openFeedViewer(
            request.source,
            FeedViewerLaunch(posts, context, liveSearchBinding = true, visibilityFilters = SearchVisibilityFilters()),
            saveContext = false,
            singleTop = false,
            saveQuery = query,
        )
    }
}
