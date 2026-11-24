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

val LocalFeedComputationContext = staticCompositionLocalOf<CoroutineContext> { Dispatchers.Default }

class ListFrame<E, T>(val list: List<E>, val inputs: Array<out Any?>, val value: T)

internal class FrameCache<E, T> {
    var frame: ListFrame<E, T>? = null
}

@Composable
fun <E, T> rememberComputedOffMain(
    list: List<E>,
    vararg inputs: Any?,
    section: String? = null,
    retainKey: String? = null,
    extend: ((previous: ListFrame<E, T>) -> T?)? = null,
    compute: () -> T,
): T {
    val cache = remember { FrameCache<E, T>() }
    val retained = retainKey?.let { rememberRetainedFrameCache<E, T>(it) }
    val published = remember { mutableIntStateOf(0) }
    val background = LocalFeedComputationContext.current
    published.intValue
    val previous = cache.frame ?: retained?.frame?.takeIf { it.list === list && it.inputs.sameAs(inputs) }
    val frame = when {
        previous == null -> ListFrame(list, inputs, traced(section, compute))
        previous.list === list -> previous
        else -> {
            val carry = extend?.takeIf { previous.inputs.sameAs(inputs) }
            ListFrame(list, inputs, traced(section) { carry?.invoke(previous) ?: compute() })
        }
    }
    cache.frame = frame
    retained?.frame = frame
    LaunchedEffect(*inputs) {
        if (frame.inputs.sameAs(inputs)) return@LaunchedEffect
        val value = withContext(background) { traced(section, compute) }
        if (cache.frame?.list !== list) return@LaunchedEffect
        cache.frame = ListFrame(list, inputs, value).also { retained?.frame = it }
        published.intValue++
    }
    return frame.value
}

private inline fun <T> traced(section: String?, block: () -> T): T =
    if (section == null) block() else traceMediaSection(section, block)

private fun Array<out Any?>.sameAs(other: Array<out Any?>): Boolean =
    size == other.size && indices.all { this[it] == other[it] }

inline fun <E> List<E>.appendedAfterRemovals(previous: List<E>, accept: (item: E, kept: Boolean) -> Boolean): List<E>? {
    var next = 0
    for (item in previous) {
        val kept = next < size && this[next] === item
        if (kept) next++
        if (!accept(item, kept)) return null
    }
    return subList(next, size)
}

fun <E> List<E>.appendedTo(previous: List<E>): List<E>? = appendedAfterRemovals(previous) { _, kept -> kept }
