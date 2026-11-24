package com.theoriacodex.app.ui.trails

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import com.theoriacodex.app.codex.SavedSearchFeedLoader
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.ui.AppRoute
import com.theoriacodex.app.ui.TheoriaShell
import com.theoriacodex.app.ui.findActivity
import com.theoriacodex.app.ui.launchViewer
import com.theoriacodex.app.ui.navigation.TrailOpener
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.app.ui.routes.BrowsingMetadata
import com.theoriacodex.app.ui.routes.browsingMetadataOwner
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.trailKey
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal class TrailLevel(val label: String, val trailKey: String, val feed: TrailFeed)

internal class TrailHandoffViewModel : ViewModel() {
    private var pending: Pair<String, TrailLevel>? = null

    fun hand(sessionId: String, level: TrailLevel) {
        pending?.second?.feed?.close()
        pending = sessionId to level
    }

    fun claim(sessionId: String?): TrailLevel? = pending?.takeIf { it.first == sessionId }?.second?.also { pending = null }

    override fun onCleared() {
        pending?.second?.feed?.close()
    }
}

@Composable
internal fun rememberTrailHandoff(): TrailHandoffViewModel =
    viewModel(viewModelStoreOwner = LocalContext.current.findActivity() as ComponentActivity)

@Composable
internal fun rememberTrailOpener(shell: TheoriaShell): TrailOpener {
    val handoff = rememberTrailHandoff()
    val metadata = browsingMetadataOwner(shell.data)
    return remember(shell, handoff, metadata) {
        TrailOpener { request -> shell.openTrail(request, handoff) { metadata.state.value } }
    }
}

private suspend fun TheoriaShell.openTrail(
    request: TrailRequest,
    handoff: TrailHandoffViewModel,
    marks: () -> BrowsingMetadata?,
): Boolean {
    val origin = navController.currentBackStackEntry
    val parent = routes.viewer.current?.session?.value?.searchVisibilityFilters
        ?.takeIf { origin?.destination?.route == AppRoute.Viewer } ?: SearchVisibilityFilters()
    val spec = trailSpec(request, shellTrailSources(), parent) ?: return false
    val filters = trailVisibilityFilters(parent, spec.query)
    val feed = TrailFeed("trail:${UUID.randomUUID()}", spec.pager, features.relatedPosts)
    var opened = false
    try {
        val posts = feed.start { results -> results.visibleUnder(filters, marks()) }
        val session = if (posts.isEmpty()) null else viewerRouteWorkflow.prepareResumableSession(
            ViewerSession(
                posts = posts,
                context = ViewerLaunchContext(feed.queryHash, 0, ViewerStreamSource.TRAIL, 0),
                liveSearchBinding = true,
                saveQuery = spec.query,
                searchVisibilityFilters = filters,
            ),
        )
        if (session != null) {
            if (!stillOver(origin, request.from)) throw CancellationException("The screen or post this trail was opened from is gone")
            handoff.hand(session.sessionId, TrailLevel(spec.label, request.trailKey(), feed))
            launchViewer(session, saveContext = false, singleTop = false)
            opened = true
        }
    } finally {
        if (!opened) feed.close()
    }
    return opened
}

private fun List<Post>.visibleUnder(filters: SearchVisibilityFilters, marks: BrowsingMetadata?): List<Post> = filterSearchResults(
    results = this,
    filters = filters,
    likedPostIds = marks?.likedPostIds.orEmpty(),
    savedPostIds = marks?.savedPostIds.orEmpty(),
    watchedPostIds = marks?.watchedPostIds.orEmpty(),
)

private fun TheoriaShell.stillOver(origin: NavBackStackEntry?, from: Post): Boolean {
    if (navController.currentBackStackEntry !== origin) return false
    if (origin?.destination?.route != AppRoute.Viewer) return true
    return routes.viewer.current?.state?.value?.currentPage?.post?.id == from.id
}

internal suspend fun TheoriaShell.shellTrailSources(): TrailSources {
    val available = sources.availableSources.value
    val runtime = data.settingsRepository.observeSettings().first().runtime
    val tagLoader = SavedSearchFeedLoader(features.search).apply {
        updateEnvironment(available, runtime.copy(enabledSources = runtime.enabledSources.intersect(available)))
    }
    return object : TrailSources {
        override fun tag(query: Query) = tagTrailPager(tagLoader, query)
        override fun creator(creator: CreatorProfile) = (sources.registry.adapterFor(creator.source) as? CreatorPostsSourceAdapter)
            ?.takeIf { creator.source in available }
            ?.let { adapter -> creatorTrailPager(adapter, creator, data.fileMirrors::learn) }
        override fun similar(seed: Post) = similarTrailPager(features.relatedPosts, seed)
        override fun termLimit(source: SourceKey) = sources.registry.adapterFor(source)?.capabilities?.searchTermLimit
    }
}
