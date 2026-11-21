package com.theoriacodex.app.ui.trails

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.codex.SavedSearchFeedLoader
import com.theoriacodex.app.ui.TheoriaShell
import com.theoriacodex.app.ui.findActivity
import com.theoriacodex.app.ui.launchViewer
import com.theoriacodex.app.ui.navigation.TrailOpener
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** A fork ready to show: its crumb label and its feed. */
internal class TrailLevel(val label: String, val feed: TrailFeed)

/**
 * Holds a fork between the opener, which loads its first page, and the Viewer entry that shows it. Activity-scoped, and
 * keyed by the fork's Viewer session so only the entry claiming that session takes it.
 */
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

/** The activity's [TrailHandoffViewModel], the same instance from the shell and from any Viewer entry. */
@Composable
internal fun rememberTrailHandoff(): TrailHandoffViewModel =
    viewModel(viewModelStoreOwner = LocalContext.current.findActivity() as ComponentActivity)

/** The shell's [TrailOpener]: loads a fork's first page, then pushes it as another Viewer entry. */
@Composable
internal fun rememberTrailOpener(shell: TheoriaShell): TrailOpener {
    val handoff = rememberTrailHandoff()
    return remember(shell, handoff) { TrailOpener { request -> shell.openTrail(request, handoff) } }
}

private suspend fun TheoriaShell.openTrail(request: TrailRequest, handoff: TrailHandoffViewModel): Boolean {
    val spec = trailSpec(request, shellTrailSources()) ?: return false
    val feed = TrailFeed("trail:${UUID.randomUUID()}", spec.pager, features.relatedPosts)
    val session = try {
        if (!feed.start()) null else viewerRouteWorkflow.prepareResumableSession(
            ViewerSession(
                posts = feed.viewerFeed.results,
                context = ViewerLaunchContext(feed.queryHash, 0, ViewerStreamSource.TRAIL, 0),
                liveSearchBinding = true,
                saveQuery = spec.query,
            ),
        )
    } catch (error: CancellationException) {
        feed.close()
        throw error
    }
    if (session == null) {
        feed.close()
        return false
    }
    handoff.hand(session.sessionId, TrailLevel(spec.label, feed))
    // A fork saves no launch context: the Viewer it covers keeps its own for restoration.
    launchViewer(session, saveContext = false, singleTop = false)
    return true
}

/** Each trail kind bound to the shell's loaders, read with the current sources and settings. */
private suspend fun TheoriaShell.shellTrailSources(): TrailSources {
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
    }
}
