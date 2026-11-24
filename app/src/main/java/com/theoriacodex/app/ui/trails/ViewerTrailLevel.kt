package com.theoriacodex.app.ui.trails

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.theoriacodex.app.ui.AppRoute
import com.theoriacodex.app.ui.routes.ViewerRouteEffectCallbacks
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.viewer.ViewerCrumb
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Query

internal class ViewerLevel(
    val isFork: Boolean,
    val crumbs: List<ViewerCrumb>,
    val feed: TrailFeed?,
    val coveredByFork: () -> Boolean,
    val popTo: (Int) -> Unit,
    val nameRoot: (ViewerLaunchContext, Query?) -> Unit,
) {
    fun clearsReturnOnDispose(): Boolean = !isFork && !coveredByFork()

    companion object {
        val Root = ViewerLevel(false, emptyList(), null, { false }, {}, { _, _ -> })
    }
}

internal class ViewerTrailEntryViewModel(val claimed: TrailLevel?) : ViewModel() {
    override fun onCleared() {
        claimed?.feed?.close()
    }
}

internal fun NavBackStackEntry.isTrailLevel(): Boolean = savedStateHandle.get<String>(TRAIL_KEY) != null

internal fun viewerRootLabel(context: ViewerLaunchContext, query: Query?): String =
    query?.includeTags?.joinToString(" ") { it.replace('_', ' ') }?.takeIf { it.isNotBlank() } ?: when (context.streamSource) {
        ViewerStreamSource.SEARCH -> "Search"
        ViewerStreamSource.FOR_YOU -> "For You"
        ViewerStreamSource.CODEX -> "Codex"
        ViewerStreamSource.CREATOR_PROFILE -> "Creator"
        ViewerStreamSource.RECENTS -> "History"
        ViewerStreamSource.RELATED -> "Related"
        ViewerStreamSource.TRAIL -> "Trail"
        ViewerStreamSource.SERIES -> "Series"
    }

@Composable
internal fun rememberViewerLevel(navController: NavHostController, entry: NavBackStackEntry, pendingSessionId: String?): ViewerLevel {
    val handoff = rememberTrailHandoff()
    val owner = viewModel<ViewerTrailEntryViewModel>(
        viewModelStoreOwner = entry,
        factory = viewModelFactory { initializer { ViewerTrailEntryViewModel(handoff.claim(pendingSessionId)) } },
    )
    val saved = entry.savedStateHandle
    owner.claimed?.let { claimed ->
        if (saved.get<String>(TRAIL_LABEL_KEY) == null) saved[TRAIL_LABEL_KEY] = claimed.label
        if (saved.get<String>(TRAIL_KEY) == null) saved[TRAIL_KEY] = claimed.trailKey
    }
    if (saved.get<ArrayList<String>>(TRAIL_ANCESTOR_IDS_KEY) == null) saved.recordAncestors(navController.previousBackStackEntry)
    val label by saved.getStateFlow<String?>(TRAIL_LABEL_KEY, null).collectAsStateWithLifecycle()
    return remember(entry, owner, label) {
        val ancestors = saved.get<ArrayList<String>>(TRAIL_ANCESTOR_LABELS_KEY).orEmpty()
        ViewerLevel(
            isFork = ancestors.isNotEmpty() || entry.isTrailLevel(),
            crumbs = ancestors.map(::ViewerCrumb) + ViewerCrumb(label ?: UNNAMED_LEVEL, saved.get<String>(TRAIL_KEY)),
            feed = owner.claimed?.feed,
            coveredByFork = { navController.currentBackStackEntry?.savedStateHandle?.get<ArrayList<String>>(TRAIL_ANCESTOR_IDS_KEY)?.contains(entry.id) == true },
            popTo = { crumb -> repeat((ancestors.size - crumb).coerceAtLeast(0)) { navController.popBackStack() } },
            nameRoot = { context, query ->
                if (saved.get<String>(TRAIL_LABEL_KEY) == null) saved[TRAIL_LABEL_KEY] = viewerRootLabel(context, query)
            },
        )
    }
}

private fun SavedStateHandle.recordAncestors(previous: NavBackStackEntry?) {
    val parent = previous?.takeIf { it.destination.route == AppRoute.Viewer }
    val parentState = parent?.savedStateHandle
    this[TRAIL_ANCESTOR_LABELS_KEY] = ArrayList(
        parentState?.let { it.get<ArrayList<String>>(TRAIL_ANCESTOR_LABELS_KEY).orEmpty() + (it.get<String>(TRAIL_LABEL_KEY) ?: UNNAMED_LEVEL) }.orEmpty(),
    )
    this[TRAIL_ANCESTOR_IDS_KEY] = ArrayList(
        parentState?.let { it.get<ArrayList<String>>(TRAIL_ANCESTOR_IDS_KEY).orEmpty() + parent.id }.orEmpty(),
    )
}

private const val TRAIL_LABEL_KEY = "viewer-trail-label"
private const val TRAIL_KEY = "viewer-trail-key"
private const val TRAIL_ANCESTOR_LABELS_KEY = "viewer-trail-ancestor-labels"
private const val TRAIL_ANCESTOR_IDS_KEY = "viewer-trail-ancestor-ids"
private const val UNNAMED_LEVEL = "Viewer"

internal fun ViewerRouteEffectCallbacks.forLevel(level: ViewerLevel, popLevel: () -> Unit): ViewerRouteEffectCallbacks = copy(
    onDismiss = if (level.isFork) ({ popLevel() }) else onDismiss,
    onRestorationUnavailable = if (level.isFork) ({ popLevel() }) else onRestorationUnavailable,
    onLoadMore = { source -> if (source == ViewerStreamSource.TRAIL) level.feed?.loadNextPage() else onLoadMore(source) },
)

internal fun viewerForkFadeIn(): EnterTransition = fadeIn(tween(TheoriaMotion.SHORT_MS))

internal fun viewerForkFadeOut(): ExitTransition = fadeOut(tween(TheoriaMotion.SHORT_MS))
