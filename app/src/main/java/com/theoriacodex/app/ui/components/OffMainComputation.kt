package com.theoriacodex.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.app.viewer.traceMediaSection
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where feed presentation work runs off the main thread: [rememberComputedOffMain] and the duration filter's
 * projection. Always [Dispatchers.Default] in the app; frame-by-frame tests provide a dispatcher they step by hand.
 */
internal val LocalFeedComputationContext = staticCompositionLocalOf<CoroutineContext> { Dispatchers.Default }

/** A value computed from exactly [list] and [inputs]. */
internal class ListFrame<E, T>(val list: List<E>, val inputs: Array<out Any?>, val value: T)

internal class FrameCache<E, T> {
    var frame: ListFrame<E, T>? = null
}

/**
 * The result of [compute] over [list] and [inputs], for whole-list work (filtering, sorting) that should not walk a
 * long feed on the main thread at every change.
 *
 * - A new [list] is computed in place, in the same frame as the rest of the screen, so paging tiles, scroll
 *   restoration and the staggered grid's lanes see the list and its result together. When [list] only appended to
 *   the previous one and [inputs] are unchanged, [extend] (if given) adds the result for the appended items alone.
 * - A change to [inputs] alone (a filter toggle, a resolved duration, a sort) recomputes on [LocalFeedComputationContext]
 *   while the previous result stays on screen, so the list never flashes empty or loading.
 *
 * - With a [retainKey], the last frame also survives the Viewer in [FeedRetainedState]. The first composition back
 *   reuses it only when [list] is the same object and [inputs] are equal, which is exactly what [compute] would
 *   return; anything else is computed in place, as on a first visit.
 *
 * [compute] and [extend] must only read [list] and [inputs]. With a [section] (one of [FeedTraceSections]), every
 * pass runs inside that trace section, on whichever thread it runs.
 */
@Composable
internal fun <E, T> rememberComputedOffMain(
    list: List<E>,
    vararg inputs: Any?,
    section: String? = null,
    retainKey: String? = null,
    extend: ((previous: T, appended: List<E>) -> T)? = null,
    compute: () -> T,
): T {
    // Every frame holds a value computed from exactly its own list and inputs, so this plain cache stays correct
    // even when a composition is discarded; [published] only asks for recomposition when an off-main value lands.
    val cache = remember { FrameCache<E, T>() }
    val retained = retainKey?.let { rememberRetainedFrameCache<E, T>(it) }
    val published = remember { mutableIntStateOf(0) }
    val background = LocalFeedComputationContext.current
    published.intValue
    val previous = cache.frame ?: retained?.frame?.takeIf { it.list === list && it.inputs.sameAs(inputs) }
    val frame = when {
        previous == null -> ListFrame(list, inputs, traced(section, compute))
        previous.list === list -> previous
        extend != null && previous.inputs.sameAs(inputs) && list.extends(previous.list) ->
            ListFrame(list, inputs, traced(section) { extend(previous.value, list.subList(previous.list.size, list.size)) })
        else -> ListFrame(list, inputs, traced(section, compute))
    }
    cache.frame = frame
    retained?.frame = frame
    LaunchedEffect(*inputs) {
        if (frame.inputs.sameAs(inputs)) return@LaunchedEffect
        val value = withContext(background) { traced(section, compute) }
        // A newer list was computed in place with these inputs meanwhile; it already covers this answer.
        if (cache.frame?.list !== list) return@LaunchedEffect
        cache.frame = ListFrame(list, inputs, value).also { retained?.frame = it }
        published.intValue++
    }
    return frame.value
}

private inline fun <T> traced(section: String?, block: () -> T): T =
    if (section == null) block() else traceMediaSection(section, block)

/** Equality, as [LaunchedEffect] compares its keys; collections check identity first, so unchanged inputs stay cheap. */
private fun Array<out Any?>.sameAs(other: Array<out Any?>): Boolean =
    size == other.size && indices.all { this[it] == other[it] }

/** True when this list starts with exactly the items of [previous], so only its tail is new. */
private fun <E> List<E>.extends(previous: List<E>): Boolean =
    size >= previous.size && previous.indices.all { this[it] === previous[it] }
