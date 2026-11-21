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
 * is about to show ([pendingSessionId]), and derives the crumbs from the Viewer entries it was opened on.
 */
@Composable
internal fun rememberViewerLevel(navController: NavHostController, entry: NavBackStackEntry, pendingSessionId: String?): ViewerLevel {
    val handoff = rememberTrailHandoff()
    val owner = viewModel<ViewerTrailEntryViewModel>(
        viewModelStoreOwner = entry,
        factory = viewModelFactory { initializer { ViewerTrailEntryViewModel(handoff.claim(pendingSessionId)) } },
    )
    val saved = entry.savedStateHandle
    owner.claimed?.let { if (saved.get<String>(TRAIL_LABEL_KEY) == null) saved[TRAIL_LABEL_KEY] = it.label }
    if (saved.get<ArrayList<String>>(TRAIL_ANCESTOR_IDS_KEY) == null) saved.recordAncestors(navController.previousBackStackEntry)
    val label by saved.getStateFlow<String?>(TRAIL_LABEL_KEY, null).collectAsStateWithLifecycle()
    return remember(entry, owner, label) {
        val ancestors = saved.get<ArrayList<String>>(TRAIL_ANCESTOR_LABELS_KEY).orEmpty()
        ViewerLevel(
            isFork = ancestors.isNotEmpty(),
            crumbs = (ancestors + (label ?: UNNAMED_LEVEL)).map(::ViewerCrumb),
            feed = owner.claimed?.feed,
            coveredByFork = { navController.currentBackStackEntry?.savedStateHandle?.get<ArrayList<String>>(TRAIL_ANCESTOR_IDS_KEY)?.contains(entry.id) == true },
            popTo = { crumb -> repeat((ancestors.size - crumb).coerceAtLeast(0)) { navController.popBackStack() } },
            nameRoot = { context, query ->
                if (saved.get<String>(TRAIL_LABEL_KEY) == null) saved[TRAIL_LABEL_KEY] = viewerRootLabel(context, query)
            },
        )
    }
}

/**
 * Records, once and while this entry is first on top, the Viewer entries it was opened on ([previous] and that
 * entry's own ancestors). Each level carries its trail in its saved state, so crumbs survive process death, when the
 * levels underneath are not composed.
 */
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
private const val TRAIL_ANCESTOR_LABELS_KEY = "viewer-trail-ancestor-labels"
private const val TRAIL_ANCESTOR_IDS_KEY = "viewer-trail-ancestor-ids"
private const val UNNAMED_LEVEL = "Viewer"

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
