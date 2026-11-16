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
 *   restoration and the staggered grid's lanes see the list and its result together. When [inputs] are unchanged,
 *   [extend] (if given) may derive it from the previous frame instead, for example adding the result for appended
 *   items alone; it returns null to compute the list whole.
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
    extend: ((previous: ListFrame<E, T>) -> T?)? = null,
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
        else -> ListFrame(list, inputs, traced(section) {
            extend?.takeIf { previous.inputs.sameAs(inputs) }?.invoke(previous) ?: compute()
        })
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

/**
 * Reads this list as [previous] with some items removed and new items appended, matching items by identity and in
 * order. [accept] sees each item of [previous], told whether it was kept, and may refuse. Returns the appended items,
 * or null when [accept] refused.
 */
internal inline fun <E> List<E>.appendedAfterRemovals(previous: List<E>, accept: (item: E, kept: Boolean) -> Boolean): List<E>? {
    var next = 0
    for (item in previous) {
        val kept = next < size && this[next] === item
        if (kept) next++
        if (!accept(item, kept)) return null
    }
    return subList(next, size)
}

/** The items appended to [previous], or null when this list does not start with exactly its items. */
internal fun <E> List<E>.appendedTo(previous: List<E>): List<E>? = appendedAfterRemovals(previous) { _, kept -> kept }
