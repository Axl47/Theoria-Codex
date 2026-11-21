package com.theoriacodex.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.theoriacodex.app.codex.LocalQuickSave
import com.theoriacodex.app.triage.TriageCardSupport
import com.theoriacodex.app.triage.TriageDeckCallbacks
import com.theoriacodex.app.triage.TriageDeckScreen
import com.theoriacodex.app.triage.TriageDeckViewModel
import com.theoriacodex.app.ui.components.feedDurations
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import com.theoriacodex.app.ui.navigation.TriageMode
import com.theoriacodex.app.ui.navigation.TriageOpener
import com.theoriacodex.app.ui.navigation.TriageRequest
import com.theoriacodex.app.ui.navigation.TriageSeed
import com.theoriacodex.app.ui.routes.BrowsingDestinationStateBoundary
import com.theoriacodex.app.ui.routes.rememberMediaDurationRouteBinding
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** [AppRoute.Triage]'s arguments. */
internal val triageArguments: List<NamedNavArgument> = listOf(
    navArgument("source") { type = NavType.StringType },
    navArgument("feedKey") { type = NavType.StringType; defaultValue = "" },
    navArgument("removes") { type = NavType.BoolType; defaultValue = false },
    navArgument("alsoIn") { type = NavType.StringType; defaultValue = "" },
)

/** The [TriageRequest] this back stack entry was opened with; an unknown source reads as Search. */
internal fun NavBackStackEntry.triageRequest(): TriageRequest {
    val source = arguments?.getString("source")
    val alsoIn = arguments?.getString("alsoIn").orEmpty().split(AppRoute.TRIAGE_ID_SEPARATOR).filter(String::isNotBlank)
    return TriageRequest(
        source = ViewerStreamSource.entries.firstOrNull { it.name == source } ?: ViewerStreamSource.SEARCH,
        feedKey = arguments?.getString("feedKey").orEmpty(),
        mode = if (arguments?.getBoolean("removes") == true) TriageMode.RemoveFromCodex(alsoIn.toSet()) else TriageMode.Browse,
    )
}

/**
 * Holds what the tapped grid handed over for the deck its [TriageRequest] opens, until the next Triage tap replaces
 * it. Activity-scoped, so a deck that rotation recreates finds it again; after process death it is empty and the deck
 * closes, back to its origin feed. Its scope runs each deck's writes, so they finish after the deck closes.
 */
internal class TriageHandOffViewModel : ViewModel() {
    private var handed: Pair<TriageRequest, TriageSeed>? = null

    fun hand(request: TriageRequest, seed: TriageSeed) {
        handed = request to seed
    }

    fun seedFor(request: TriageRequest): TriageSeed? = handed?.takeIf { it.first == request }?.second
}

/** The activity's [TriageHandOffViewModel], the same instance from the shell and from the deck's entry. */
@Composable
private fun rememberTriageHandOff(): TriageHandOffViewModel =
    viewModel(viewModelStoreOwner = LocalContext.current.findActivity() as ComponentActivity)

/**
 * Opens the triage deck by pushing [AppRoute.Triage]. A feed's menu calls it with the feed's own source and key
 * (Search, For You and a Creator page with their query hash, a Codex or Likes with `codex:<id>`) and what its grid
 * shows. A second tap meanwhile replaces the first.
 */
@Composable
internal fun rememberTriageOpener(shell: TheoriaShell): TriageOpener {
    val handOff = rememberTriageHandOff()
    return remember(shell, handOff) {
        var opening: Job? = null
        TriageOpener { request, seed ->
            opening?.cancel()
            opening = shell.scope.launch { shell.openTriage(request, seed, handOff) }
        }
    }
}

/**
 * Pushes the deck over [request] once Search's pending filters have applied (see [settledTriage]), but only over the
 * screen and tab Triage was tapped on: when the user has moved on meanwhile it opens nothing.
 */
private suspend fun TheoriaShell.openTriage(request: TriageRequest, seed: TriageSeed, handOff: TriageHandOffViewModel) {
    val origin = navController.currentBackStackEntry
    val tab = navigation.homeTabRoute
    val (settled, settledSeed) = settledTriage(request, seed)
    if (navController.currentBackStackEntry !== origin || navigation.homeTabRoute != tab) return
    handOff.hand(settled, settledSeed)
    navController.navigate(AppRoute.triage(settled))
}

/**
 * The triage deck over [request]'s feed, seeded from what its grid handed over; with no hand-off, such as after
 * process death, it closes.
 */
@Composable
internal fun TriageDestination(shell: TheoriaShell, request: TriageRequest) {
    val handOff = rememberTriageHandOff()
    val seed = handOff.seedFor(request)
    // The deck's owner keeps the feed it was built with; a rebuilt one only supplies the posts to resolve lengths for.
    val feed = remember(request, seed) { seed?.let { shell.triageFeed(request, it) } }
    if (seed == null || feed == null) {
        LaunchedEffect(request) { shell.navController.popBackStack() }
        Box(Modifier.fillMaxSize())
        return
    }
    val query = shell.triageQuery(request)
    val quickSave = LocalQuickSave.current
    val owner = viewModel<TriageDeckViewModel> {
        val fromForYou = request.source == ViewerStreamSource.FOR_YOU
        TriageDeckViewModel(
            feed, ShellTriageActions(shell), quickSave, query, fromForYou, handOff.viewModelScope, shell.triageRemoval(request),
        )
    }
    val deck by owner.state.collectAsStateWithLifecycle()
    // While the grid filters by length, the deck resolves the lengths of the owner's posts it has yet to show.
    val candidates by remember(feed, seed) { if (seed.lengthFilterActive) feed.candidates else flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())
    val durationPosts = remember(deck.posts, candidates) { if (candidates.isEmpty()) deck.posts else (deck.posts + candidates).distinctBy(Post::id) }
    BrowsingDestinationStateBoundary(shell.data, shell.sources) { state ->
        val duration = rememberMediaDurationRouteBinding(
            coordinator = shell.features.mediaDurationCoordinator,
            routeName = "triage",
            ownerKey = "triage-duration-owner",
            contentIdentity = request.feedKey,
            posts = durationPosts,
            resolveInBackground = false,
        )
        LaunchedEffect(duration.owner, seed.lengthFilterActive) { duration.owner.onFilterChanged(seed.lengthFilterActive) }
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
                onOpenPost = { posts, index -> shell.openTriagePost(request, seed, query, posts, index) },
                onOpenSavePicker = { post ->
                    shell.requestSaveToCodex(post, fromForYou = request.source == ViewerStreamSource.FOR_YOU, searchQuery = query)
                },
                onClose = { shell.navController.popBackStack() },
            ),
        )
    }
}

/**
 * Search is the one feed whose query the deck can read, while Search still shows the query the deck opened over;
 * the others rank saves and hide tags without one.
 */
private fun TheoriaShell.triageQuery(request: TriageRequest): Query? =
    routes.search.current?.state?.value?.query
        ?.takeIf { request.source == ViewerStreamSource.SEARCH && it.appliedQueryHash == request.feedKey }?.applied

/**
 * Opens the Viewer on the deck's post [index]. A live feed's Viewer keeps following its owner while it shows the
 * deck's query, with the filters its grid handed over ([seed]), as a tap on that feed's own grid does, so paging in
 * the deck and in the Viewer share one feed.
 */
private fun TheoriaShell.openTriagePost(request: TriageRequest, seed: TriageSeed, query: Query?, posts: List<Post>, index: Int) {
    request.codexId?.let { codexId -> return openCodexViewer(codexId, posts, index) }
    val context = ViewerLaunchContext(request.feedKey, index, request.source, scrollOffsetHint = 0)
    scope.launch {
        openFeedViewer(
            request.source,
            FeedViewerLaunch(posts, context, liveSearchBinding = true, visibilityFilters = seed.filters),
            saveContext = false,
            singleTop = false,
            saveQuery = query,
        )
    }
}
