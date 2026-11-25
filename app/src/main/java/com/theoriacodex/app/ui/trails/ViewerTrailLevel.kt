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
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.app.viewer.CrumbTag
import com.theoriacodex.app.viewer.ViewerCrumb
import com.theoriacodex.app.viewer.removal
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey

internal class ViewerLevel(
    val isFork: Boolean,
    val crumbs: List<ViewerCrumb>,
    val feed: TrailFeed?,
    val coveredByFork: () -> Boolean,
    val popTo: (Int) -> Unit,
    val nameRoot: (ViewerLaunchContext, Query?) -> Unit,
    val remove: (Int) -> Unit = {},
    val replay: CrumbTag? = null,
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
        if (!saved.contains(TRAIL_REPLAY_KEY)) saved.recordStep(claimed)
    }
    if (saved.get<ArrayList<String>>(TRAIL_ANCESTOR_IDS_KEY) == null) saved.recordAncestors(navController.previousBackStackEntry)
    val label by saved.getStateFlow<String?>(TRAIL_LABEL_KEY, null).collectAsStateWithLifecycle()
    val replay by saved.getStateFlow(TRAIL_REPLAY_KEY, ArrayList<String>()).collectAsStateWithLifecycle()
    return remember(entry, owner, label, replay) {
        val ancestors = saved.get<ArrayList<String>>(TRAIL_ANCESTOR_LABELS_KEY).orEmpty()
        val ancestorIds = saved.get<ArrayList<String>>(TRAIL_ANCESTOR_IDS_KEY).orEmpty()
        val stack = navController.currentBackStack.value
        val crumbs = ancestors.mapIndexed { index, ancestor ->
            stack.firstOrNull { it.id == ancestorIds.getOrNull(index) }?.savedStateHandle?.crumb(ancestor) ?: ViewerCrumb(ancestor)
        } + saved.crumb(label ?: UNNAMED_LEVEL)
        val popTo: (Int) -> Unit = { crumb -> repeat((ancestors.size - crumb).coerceAtLeast(0)) { navController.popBackStack() } }
        ViewerLevel(
            isFork = ancestors.isNotEmpty() || entry.isTrailLevel(),
            crumbs = crumbs,
            feed = owner.claimed?.feed,
            coveredByFork = { navController.currentBackStackEntry?.savedStateHandle?.get<ArrayList<String>>(TRAIL_ANCESTOR_IDS_KEY)?.contains(entry.id) == true },
            popTo = popTo,
            nameRoot = { context, query ->
                if (saved.get<String>(TRAIL_LABEL_KEY) == null) saved[TRAIL_LABEL_KEY] = viewerRootLabel(context, query)
            },
            remove = { index ->
                crumbs.removal(index)?.let { removal ->
                    popTo(removal.backTo)
                    navController.currentBackStackEntry?.savedStateHandle?.planTrailReplay(removal.replay)
                }
            },
            replay = replay.firstOrNull()?.let(::decodeReplayTag),
        )
    }
}

internal fun SavedStateHandle.trailReplayAfter(request: TrailRequest): List<CrumbTag> {
    val pending = get<ArrayList<String>>(TRAIL_REPLAY_KEY).orEmpty().mapNotNull(::decodeReplayTag)
    val opened = (request as? TrailRequest.Tag)?.let { CrumbTag(it.tag, it.source) }
    if (pending.isNotEmpty() && opened == pending.first()) return pending.drop(1)
    clearTrailReplay()
    return emptyList()
}

internal fun SavedStateHandle.clearTrailReplay() {
    if (get<ArrayList<String>>(TRAIL_REPLAY_KEY).orEmpty().isNotEmpty()) this[TRAIL_REPLAY_KEY] = ArrayList<String>()
}

internal fun SavedStateHandle.planTrailReplay(tags: List<CrumbTag>) {
    if (tags.isNotEmpty()) this[TRAIL_REPLAY_KEY] = encodeReplay(tags)
}

private fun encodeReplay(tags: List<CrumbTag>): ArrayList<String> = tags.mapTo(ArrayList()) { "${it.source.id}$REPLAY_SEPARATOR${it.value}" }

private fun decodeReplayTag(encoded: String): CrumbTag? {
    val source = SourceKey.parse(encoded.substringBefore(REPLAY_SEPARATOR, "")) ?: return null
    return CrumbTag(encoded.substringAfter(REPLAY_SEPARATOR), source)
}

private fun SavedStateHandle.recordStep(level: TrailLevel) {
    level.tag?.let { tag ->
        this[TRAIL_TAG_KEY] = tag.value
        this[TRAIL_TAG_SOURCE_KEY] = tag.source.id
    }
    this[TRAIL_REPLAY_KEY] = encodeReplay(level.replay)
}

private fun SavedStateHandle.crumb(label: String): ViewerCrumb {
    val tag = get<String>(TRAIL_TAG_KEY)
    val source = get<String>(TRAIL_TAG_SOURCE_KEY)?.let { SourceKey.parse(it) }
    return ViewerCrumb(label, get<String>(TRAIL_KEY), if (tag != null && source != null) CrumbTag(tag, source) else null)
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
private const val TRAIL_TAG_KEY = "viewer-trail-tag"
private const val TRAIL_TAG_SOURCE_KEY = "viewer-trail-tag-source"
private const val TRAIL_REPLAY_KEY = "viewer-trail-replay"
private const val REPLAY_SEPARATOR = '\n'
private const val UNNAMED_LEVEL = "Viewer"

internal fun ViewerRouteEffectCallbacks.forLevel(level: ViewerLevel, popLevel: () -> Unit): ViewerRouteEffectCallbacks = copy(
    onDismiss = if (level.isFork) ({ popLevel() }) else onDismiss,
    onRestorationUnavailable = if (level.isFork) ({ popLevel() }) else onRestorationUnavailable,
    onLoadMore = { source -> if (source == ViewerStreamSource.TRAIL) level.feed?.loadNextPage() else onLoadMore(source) },
)

internal fun viewerForkFadeIn(): EnterTransition = fadeIn(tween(TheoriaMotion.SHORT_MS))

internal fun viewerForkFadeOut(): ExitTransition = fadeOut(tween(TheoriaMotion.SHORT_MS))
