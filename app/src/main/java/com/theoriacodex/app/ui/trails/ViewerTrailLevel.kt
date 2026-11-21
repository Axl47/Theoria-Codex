package com.theoriacodex.app.ui.trails

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

/**
 * One Viewer entry's place in a trail. A fork ([isFork]) sits on another Viewer entry: dismissing it pops just that
 * level and leaves the card return and Search's launch context to the root. [feed] is the fork's live feed, null for
 * a root or a fork restored after process death (which shows its saved window without paging).
 */
internal class ViewerLevel(
    val isFork: Boolean,
    val crumbs: List<ViewerCrumb>,
    val feed: TrailFeed?,
    /** Whether a fork now covers this entry; read when the entry leaves composition. */
    val coveredByFork: () -> Boolean,
    /** Pops back to the crumb at an index of [crumbs]. */
    val popTo: (Int) -> Unit,
    /** Names a root level after what it was opened from; forks keep their trail's name. */
    val nameRoot: (ViewerLaunchContext, Query?) -> Unit,
) {
    /**
     * Whether this entry leaving composition ends the card return: only a root that no fork covers owns it. A covered
     * root keeps its card's origin for when the user comes back down the trail and closes it.
     */
    fun clearsReturnOnDispose(): Boolean = !isFork && !coveredByFork()

    companion object {
        val Root = ViewerLevel(false, emptyList(), null, { false }, {}, { _, _ -> })
    }
}

/** Keeps the fork this entry claimed for as long as the entry is on the back stack. */
internal class ViewerTrailEntryViewModel(val claimed: TrailLevel?) : ViewModel() {
    override fun onCleared() {
        claimed?.feed?.close()
    }
}

/** The run of Viewer entries ending at [index]: the trail [index] belongs to, oldest first. Empty when it is no Viewer. */
internal fun viewerTrailRun(isViewer: List<Boolean>, index: Int): IntRange {
    if (isViewer.getOrNull(index) != true) return IntRange.EMPTY
    var start = index
    while (start > 0 && isViewer[start - 1]) start--
    return start..index
}

/** A root level's crumb: the search it shows, or where it was opened from. */
internal fun viewerRootLabel(context: ViewerLaunchContext, query: Query?): String =
    query?.includeTags?.joinToString(" ") { it.replace('_', ' ') }?.takeIf { it.isNotBlank() } ?: when (context.streamSource) {
        ViewerStreamSource.SEARCH -> "Search"
        ViewerStreamSource.FOR_YOU -> "For You"
        ViewerStreamSource.CODEX -> "Codex"
        ViewerStreamSource.CREATOR_PROFILE -> "Creator"
        ViewerStreamSource.RECENTS -> "Recents"
        ViewerStreamSource.RELATED -> "Related"
        ViewerStreamSource.TRAIL -> "Trail"
    }

/**
 * Binds [entry], a Viewer destination, to its trail: claims the fork the opener handed over for the session this entry
 * is about to show ([pendingSessionId]), and derives the crumbs from the Viewer entries stacked under it.
 */
@Composable
internal fun rememberViewerLevel(navController: NavHostController, entry: NavBackStackEntry, pendingSessionId: String?): ViewerLevel {
    val handoff = rememberTrailHandoff()
    val owner = viewModel<ViewerTrailEntryViewModel>(
        viewModelStoreOwner = entry,
        factory = viewModelFactory { initializer { ViewerTrailEntryViewModel(handoff.claim(pendingSessionId)) } },
    )
    owner.claimed?.let { if (entry.savedStateHandle.get<String>(TRAIL_LABEL_KEY) == null) entry.savedStateHandle[TRAIL_LABEL_KEY] = it.label }
    val stack by navController.currentBackStack.collectAsStateWithLifecycle()
    return remember(stack, entry, owner) {
        val index = stack.indexOf(entry)
        val isViewer = stack.map { it.destination.route == AppRoute.Viewer }
        val run = viewerTrailRun(isViewer, index)
        ViewerLevel(
            isFork = run.first < index,
            crumbs = run.map { ViewerCrumb(stack[it].savedStateHandle.get<String>(TRAIL_LABEL_KEY) ?: "Viewer") },
            feed = owner.claimed?.feed,
            coveredByFork = {
                val live = navController.currentBackStack.value
                live.getOrNull(live.indexOf(entry) + 1)?.destination?.route == AppRoute.Viewer
            },
            popTo = { crumb ->
                val live = navController.currentBackStack.value
                repeat((live.indexOf(entry) - (run.first + crumb)).coerceAtLeast(0)) { navController.popBackStack() }
            },
            nameRoot = { context, query ->
                if (entry.savedStateHandle.get<String>(TRAIL_LABEL_KEY) == null) entry.savedStateHandle[TRAIL_LABEL_KEY] = viewerRootLabel(context, query)
            },
        )
    }
}

private const val TRAIL_LABEL_KEY = "viewer-trail-label"

/**
 * These callbacks adjusted for [level]: a fork's dismiss only pops its own entry (leaving Search's launch context alone), and its feed pages the TRAIL stream, which no
 * shell-wide feed owner serves.
 */
internal fun ViewerRouteEffectCallbacks.forLevel(level: ViewerLevel, popLevel: () -> Unit): ViewerRouteEffectCallbacks = copy(
    onDismiss = if (level.isFork) ({ popLevel() }) else onDismiss,
    // A fork restored after process death has no feed to resume; it closes back to the level under it.
    onRestorationUnavailable = if (level.isFork) ({ popLevel() }) else onRestorationUnavailable,
    onLoadMore = { source -> if (source == ViewerStreamSource.TRAIL) level.feed?.loadNextPage() else onLoadMore(source) },
)

/** Viewer-to-Viewer navigation (a fork opening or closing) is a short fade rather than a hard cut. */
internal fun viewerForkFadeIn(): EnterTransition = fadeIn(tween(TheoriaMotion.SHORT_MS))

internal fun viewerForkFadeOut(): ExitTransition = fadeOut(tween(TheoriaMotion.SHORT_MS))
