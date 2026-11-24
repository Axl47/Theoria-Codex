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
import com.theoriacodex.app.codex.ChosenCodex
import com.theoriacodex.app.codex.LocalNewCodexOpener
import com.theoriacodex.app.codex.LocalQuickSave
import com.theoriacodex.app.codex.NewCodexOpener
import com.theoriacodex.app.triage.TriageCardSupport
import com.theoriacodex.app.triage.TriageChoice
import com.theoriacodex.app.triage.TriageDeck
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

internal val triageArguments: List<NamedNavArgument> = listOf(
    navArgument("source") { type = NavType.StringType },
    navArgument("feedKey") { type = NavType.StringType; defaultValue = "" },
    navArgument("removes") { type = NavType.BoolType; defaultValue = false },
    navArgument("alsoIn") { type = NavType.StringType; defaultValue = "" },
)

internal fun NavBackStackEntry.triageRequest(): TriageRequest {
    val source = arguments?.getString("source")
    val alsoIn = arguments?.getString("alsoIn").orEmpty().split(AppRoute.TRIAGE_ID_SEPARATOR).filter(String::isNotBlank)
    return TriageRequest(
        source = ViewerStreamSource.entries.firstOrNull { it.name == source } ?: ViewerStreamSource.SEARCH,
        feedKey = arguments?.getString("feedKey").orEmpty(),
        mode = if (arguments?.getBoolean("removes") == true) TriageMode.RemoveFromCodex(alsoIn.toSet()) else TriageMode.Browse,
    )
}

internal class TriageHandOffViewModel : ViewModel() {
    private var handed: Pair<TriageRequest, TriageSeed>? = null

    fun hand(request: TriageRequest, seed: TriageSeed) {
        handed = request to seed
    }

    fun seedFor(request: TriageRequest): TriageSeed? = handed?.takeIf { it.first == request }?.second
}

@Composable
internal fun rememberTriageHandOff(): TriageHandOffViewModel =
    viewModel(viewModelStoreOwner = LocalContext.current.findActivity() as ComponentActivity)

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

private suspend fun TheoriaShell.openTriage(request: TriageRequest, seed: TriageSeed, handOff: TriageHandOffViewModel) {
    val origin = navController.currentBackStackEntry
    val tab = navigation.homeTabRoute
    val (settled, settledSeed) = settledTriage(request, seed)
    if (navController.currentBackStackEntry !== origin || navigation.homeTabRoute != tab) return
    handOff.hand(settled, settledSeed)
    navController.navigate(AppRoute.triage(settled))
}

@Composable
internal fun TriageDestination(shell: TheoriaShell, request: TriageRequest) {
    val handOff = rememberTriageHandOff()
    val seed = handOff.seedFor(request)
    val feed = remember(request, seed) { seed?.let { shell.triageFeed(request, it) } }
    if (seed == null || feed == null) {
        LaunchedEffect(request) { shell.navController.popBackStack() }
        Box(Modifier.fillMaxSize())
        return
    }
    val query = shell.triageQuery(request)
    val quickSave = LocalQuickSave.current
    val owner = viewModel<TriageDeckViewModel> {
        TriageDeckViewModel(
            feed, ShellTriageActions(shell), quickSave, query, handOff.viewModelScope, shell.triageRemoval(request),
            pictures = shell.features.pictureTags,
        )
    }
    val deck by owner.deck.state.collectAsStateWithLifecycle()
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
            deck = owner.deck,
            query = query,
            support = support,
            callbacks = shell.triageCallbacks(request, seed, query, owner.deck, LocalNewCodexOpener.current),
        )
    }
}

private fun TheoriaShell.triageCallbacks(
    request: TriageRequest,
    seed: TriageSeed,
    query: Query?,
    deck: TriageDeck,
    newCodex: NewCodexOpener,
): TriageDeckCallbacks {
    val save = { post: Post -> { codex: ChosenCodex -> deck.decide(TriageChoice.Save(codex), post.id) } }
    return TriageDeckCallbacks(
        onOpenPost = { posts, index -> openTriagePost(request, seed, query, posts, index) },
        onOpenSavePicker = { post ->
            pendingSave.request(listOf(post), searchQuery = query, onPick = save(post))
        },
        onNewCodex = { post -> newCodex.openNewCodex { codex -> save(post)(codex) } },
        onClose = { navController.popBackStack() },
    )
}

private fun TheoriaShell.triageQuery(request: TriageRequest): Query? =
    routes.search.current?.state?.value?.query
        ?.takeIf { request.source == ViewerStreamSource.SEARCH && it.appliedQueryHash == request.feedKey }?.applied

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
